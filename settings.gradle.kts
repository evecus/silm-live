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
        maven("https://jitpack.io")
        // GSY 版 ijkplayer (io.github.carguo) 已发布到 mavenCentral, 不再需要 jcenter/阿里云镜像.
    }
}

rootProject.name = "silm-live"
include(":app")
