import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import dev.mimir.build.AndroidSdk
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Android-таргет KMP-модуля; без SDK ничего не делает.
if (AndroidSdk.isAvailable(rootDir)) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    val group = providers.gradleProperty("mimir.group").get()
    extensions.configure<KotlinMultiplatformExtension> {
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
            namespace = "$group.${project.name.replace('-', '.')}"
            compileSdk = 36
            minSdk = 26
        }
    }
}
