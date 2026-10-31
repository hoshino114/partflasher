pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            name = "AliyunGoogle"
        }
        maven("https://maven.aliyun.com/repository/central") {
            name = "AliyunCentral"
        }
        maven("https://maven.aliyun.com/repository/gradle-plugin") {
            name = "AliyunGradlePlugin"
        }
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google") {
            name = "AliyunGoogle"
        }
        maven("https://maven.aliyun.com/repository/central") {
            name = "AliyunCentral"
        }
    }
}

rootProject.name = "PartFlasher"
include(":app")
