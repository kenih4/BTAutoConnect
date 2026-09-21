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
        // MSAL(Microsoftサインイン)の依存 display-mask はこのフィードでのみ配布されている
        maven {
            url = uri("https://pkgs.dev.azure.com/MicrosoftDeviceSDK/DuoSDK-Public/_packaging/Duo-SDK-Feed/maven/v1")
            content {
                includeGroup("com.microsoft.device.display")
            }
        }
    }
}

rootProject.name = "BTAutoConnect"
include(":app")
