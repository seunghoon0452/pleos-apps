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
        // Pleos Playground 공개 저장소 (로그인 불필요). Pleos SDK 묶음만 여기서 받는다.
        maven {
            url = uri("https://nexus-playground.pleos.ai/repository/maven-releases/")
            content { includeGroupByRegex("ai\\.pleos\\..*") }
        }
    }
}

rootProject.name = "pleos-contest"

// 순수 Kotlin 모듈: 에뮬레이터 없이 빌드·테스트할 수 있는 핵심 로직
include(":core:common")
include(":engine:mirichaeum")
include(":engine:ansim")
include(":engine:neighbor")

// 안드로이드 모듈: Pleos SDK 연결, 공통 화면, 두 앱
include(":platform:pleos")
include(":core:ui")
include(":app:ansim")
include(":app:mirichaeum")
include(":app:neighbor")
