package com.google.devtools.ksp

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Variance
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptorWithSource
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.MemberDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.load.java.JavaDescriptorVisibilities
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinaryClass
import org.jetbrains.kotlin.load.kotlin.KotlinJvmBinarySourceElement
import org.jetbrains.kotlin.load.kotlin.VirtualFileKotlinClass
import org.jetbrains.kotlin.load.kotlin.getContainingKotlinJvmBinaryClass
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.psi.KtDeclarationWithInitializer
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.resolve.descriptorUtil.isCompanionObject
import org.jetbrains.kotlin.resolve.source.KotlinSourceElement
import org.jetbrains.kotlin.resolve.source.getPsi
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedClassDescriptor
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedPropertyDescriptor
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.org.objectweb.asm.ClassReader
import org.jetbrains.org.objectweb.asm.ClassVisitor
import org.jetbrains.org.objectweb.asm.FieldVisitor
import org.jetbrains.org.objectweb.asm.MethodVisitor
import org.jetbrains.org.objectweb.asm.Opcodes

fun MemberDescriptor.toKSModifiers(): Set<Modifier> {
    val modifiers = mutableSetOf<Modifier>()
    if (this.isActual) {
        modifiers.add(Modifier.ACTUAL)
    }
    if (this.isExpect) {
        modifiers.add(Modifier.EXPECT)
    }
    if (this.isExternal) {
        modifiers.add(Modifier.EXTERNAL)
    }
    // we are not checking for JVM_STATIC annotation here intentionally
    // see: https://github.com/google/ksp/issues/378
    val isStatic = (this.containingDeclaration as? ClassDescriptor)?.let { containingClass ->
        containingClass.staticScope.getContributedDescriptors(
            nameFilter = {
                it == this.name
            }
        ).any {
            it == this
        }
    } ?: false
    if (isStatic) {
        modifiers.add(Modifier.JAVA_STATIC)
    }
    when (this.modality) {
        Modality.SEALED -> modifiers.add(Modifier.SEALED)
        Modality.FINAL -> modifiers.add(Modifier.FINAL)
        Modality.OPEN -> {
            if (!isStatic && this.visibility != DescriptorVisibilities.PRIVATE) {
                // private methods still show up as OPEN
                modifiers.add(Modifier.OPEN)
            }
        }
        Modality.ABSTRACT -> modifiers.add(Modifier.ABSTRACT)
    }
    when (this.visibility) {
        DescriptorVisibilities.PUBLIC -> modifiers.add(Modifier.PUBLIC)
        DescriptorVisibilities.PROTECTED,
        JavaDescriptorVisibilities.PROTECTED_AND_PACKAGE,
        JavaDescriptorVisibilities.PROTECTED_STATIC_VISIBILITY,
        -> modifiers.add(Modifier.PROTECTED)
        DescriptorVisibilities.PRIVATE, DescriptorVisibilities.LOCAL -> modifiers.add(Modifier.PRIVATE)
        DescriptorVisibilities.INTERNAL -> modifiers.add(Modifier.INTERNAL)
        // Since there is no modifier for package-private, use No modifier to tell if a symbol from binary is package private.
        JavaDescriptorVisibilities.PACKAGE_VISIBILITY, JavaDescriptorVisibilities.PROTECTED_STATIC_VISIBILITY -> Unit
        else -> throw IllegalStateException("unhandled visibility: ${this.visibility}")
    }

    return modifiers
}

fun FunctionDescriptor.toFunctionKSModifiers(): Set<Modifier> {
    val modifiers = mutableSetOf<Modifier>()
    if (this.isSuspend) {
        modifiers.add(Modifier.SUSPEND)
    }
    if (this.isTailrec) {
        modifiers.add(Modifier.TAILREC)
    }
    if (this.isInline) {
        modifiers.add(Modifier.INLINE)
    }
    if (this.isInfix) {
        modifiers.add(Modifier.INFIX)
    }
    if (this.isOperator) {
        modifiers.add(Modifier.OPERATOR)
    }
    if (this.overriddenDescriptors.isNotEmpty()) {
        modifiers.add(Modifier.OVERRIDE)
    }

    return modifiers
}

fun org.jetbrains.kotlin.types.Variance.toKSVariance(): Variance {
    return when (this) {
        org.jetbrains.kotlin.types.Variance.IN_VARIANCE -> Variance.CONTRAVARIANT
        org.jetbrains.kotlin.types.Variance.OUT_VARIANCE -> Variance.COVARIANT
        org.jetbrains.kotlin.types.Variance.INVARIANT -> Variance.INVARIANT
        else -> throw IllegalStateException("Unexpected variance value $this, $ExceptionMessage")
    }
}

fun DeclarationDescriptor.findPsi(): PsiElement? {
    // For synthetic members.
    if ((this is CallableMemberDescriptor) && this.kind != CallableMemberDescriptor.Kind.DECLARATION) return null
    return (this as? DeclarationDescriptorWithSource)?.source?.getPsi()
}

/**
 * Custom check for backing fields of descriptors that support properties coming from .class files.
 * The compiler API always returns true for them even when they don't have backing fields.
 */
fun PropertyDescriptor.hasBackingFieldWithBinaryClassSupport(): Boolean {
    // partially take from https://github.com/JetBrains/kotlin/blob/master/compiler/light-classes/src/org/jetbrains/kotlin/asJava/classes/ultraLightMembersCreator.kt#L104
    return when {
        extensionReceiverParameter != null -> false // extension properties do not have backing fields
        compileTimeInitializer != null -> true // compile time initialization requires backing field
        isLateInit -> true // lateinit requires property, faster than parsing class declaration
        modality == Modality.ABSTRACT -> false // abstract means false, faster than parsing class declaration
        this is DeserializedPropertyDescriptor -> this.hasBackingFieldInBinaryClass() // kotlin class, check binary
        this.source is KotlinSourceElement -> this.declaresDefaultValue // kotlin source
        else -> true // Java source or class
    }
}

data class BinaryClassInfo(
    val fieldAccFlags: Map<String, Int>,
    val methodAccFlags: Map<String, Int>
)

/**
 * Lookup cache for field names names for deserialized classes.
 * To check if a field has backing field, we need to look for binary field names, hence they are cached here.
 */
object BinaryClassInfoCache : KSObjectCache<ClassId, BinaryClassInfo>() {
    fun getCached(
        kotlinJvmBinaryClass: KotlinJvmBinaryClass,
    ) = cache.getOrPut(kotlinJvmBinaryClass.classId) {
        val virtualFileContent = kotlinJvmBinaryClass.safeAs<VirtualFileKotlinClass>()?.file?.contentsToByteArray()
        val fieldAccFlags = mutableMapOf<String, Int>()
        val methodAccFlags = mutableMapOf<String, Int>()
        ClassReader(virtualFileContent).accept(
            object : ClassVisitor(Opcodes.API_VERSION) {
                override fun visitField(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    value: Any?
                ): FieldVisitor? {
                    if (name != null) {
                        fieldAccFlags.put(name, access)
                    }
                    return null
                }

                override fun visitMethod(
                    access: Int,
                    name: String?,
                    descriptor: String?,
                    signature: String?,
                    exceptions: Array<out String>?
                ): MethodVisitor? {
                    if (name != null) {
                        methodAccFlags.put(name + descriptor, access)
                    }
                    return null
                }
            },
            ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES
        )
        BinaryClassInfo(fieldAccFlags, methodAccFlags)
    }
}

/**
 * Workaround for backingField in deserialized descriptors.
 * They always return non-null for backing field even when they don't have a backing field.
 */
private fun DeserializedPropertyDescriptor.hasBackingFieldInBinaryClass(): Boolean {
    val kotlinJvmBinaryClass = if (containingDeclaration.isCompanionObject()) {
        // Companion objects have backing fields in containing classes.
        // https://kotlinlang.org/docs/java-to-kotlin-interop.html#static-fields
        val container = containingDeclaration.containingDeclaration as? DeserializedClassDescriptor
        container?.source?.safeAs<KotlinJvmBinarySourceElement>()?.binaryClass
    } else {
        this.getContainingKotlinJvmBinaryClass()
    } ?: return false
    return BinaryClassInfoCache.getCached(kotlinJvmBinaryClass).fieldAccFlags.containsKey(name.asString())
}

// from: https://github.com/JetBrains/kotlin/blob/92d200e093c693b3c06e53a39e0b0973b84c7ec5/plugins/kotlin-serialization/kotlin-serialization-compiler/src/org/jetbrains/kotlinx/serialization/compiler/resolve/SerializableProperty.kt#L45
private val PropertyDescriptor.declaresDefaultValue: Boolean
    get() = when (val declaration = this.source.getPsi()) {
        is KtDeclarationWithInitializer -> declaration.initializer != null
        is KtParameter -> declaration.defaultValue != null
        else -> false
    }

fun KSAnnotated.hasAnnotation(fqn: String): Boolean =
    annotations.any {
        fqn.endsWith(it.shortName.asString()) &&
            it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn
    }
