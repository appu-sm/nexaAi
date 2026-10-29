pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        ivy {
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout { artifact("v[revision]/[artifact]-[revision].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.k2fsa", "sherpa-onnx") }
        }
    }
}
rootProject.name = "NexaAI"
val appVersionName = "0.1.0"
val appVersionCode = 1
val apkBuildTypes = listOf("debug", "release")

gradle.beforeProject {
    version = appVersionName
    extensions.extraProperties["appVersionCode"] = appVersionCode
    extensions.extraProperties["apkBuildTypes"] = apkBuildTypes
}

include(":app")