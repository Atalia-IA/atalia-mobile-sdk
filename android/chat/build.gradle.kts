import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.SonatypeHost

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

// A versão publicada vem da tag do release (-PVERSION_NAME=0.1.0, ver .github/workflows/release.yml).
// Fora do release o build é sempre SNAPSHOT, para nunca sair uma versão final por engano.
val sdkVersion = providers.gradleProperty("VERSION_NAME").getOrElse("0.0.0-SNAPSHOT")

group = "ai.atalia"
version = sdkVersion

android {
    namespace = "ai.atalia.chat"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            // Fixtures compartilhadas pelos quatro SDKs (protocol/fixtures na raiz do repositório).
            it.systemProperty("atalia.fixtures", rootProject.file("../protocol/fixtures").absolutePath)
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    // O org.json do android.jar é só stub nos testes de JVM.
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// Maven Central (Central Portal). Credenciais e chave de assinatura chegam por variáveis de
// ambiente no workflow de release (ORG_GRADLE_PROJECT_mavenCentralUsername, ...signingInMemoryKey...).
mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    // automaticRelease = false: o pacote sobe e fica esperando o "Publish" no central.sonatype.com.
    // Lá nada se apaga depois de publicado, então a última conferência é de uma pessoa.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    // Assina quando a chave existe (sempre no release; o workflow recusa rodar sem ela). Sem chave,
    // o publishToMavenLocal de quem desenvolve funciona sem precisar do GPG da Atalia.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    coordinates("ai.atalia", "chat", sdkVersion)

    pom {
        name.set("Atalia Chat")
        description.set("Chat de atendimento da Atalia (webchat) para apps Android.")
        inceptionYear.set("2026")
        url.set("https://github.com/Atalia-IA/atalia-mobile-sdk")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("atalia")
                name.set("Atalia")
                email.set("suporte@atalia.com.br")
                organization.set("INCI BRASIL SERVICOS DE APERFEICOAMENTO PROFISSIONAL LTDA")
                organizationUrl.set("https://atalia.ai")
            }
        }
        scm {
            url.set("https://github.com/Atalia-IA/atalia-mobile-sdk")
            connection.set("scm:git:https://github.com/Atalia-IA/atalia-mobile-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/Atalia-IA/atalia-mobile-sdk.git")
        }
    }
}
