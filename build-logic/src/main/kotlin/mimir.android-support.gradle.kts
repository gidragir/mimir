import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import dev.mimir.build.AndroidSdk
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// Android-таргет KMP-модуля; без SDK ничего не делает.
if (AndroidSdk.isAvailable(rootDir)) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    val group = providers.gradleProperty("mimir.group").get()
    val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    fun sdk(alias: String) = catalog.findVersion(alias).get().requiredVersion.toInt()
    extensions.configure<KotlinMultiplatformExtension> {
        (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("androidLibrary") {
            namespace = "$group.${project.name.replace('-', '.')}"
            compileSdk = sdk("androidCompileSdk")
            minSdk = sdk("androidMinSdk")
        }
    }
}
