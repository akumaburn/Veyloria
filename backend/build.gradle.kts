plugins {
    java
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

// Dependency versions
val bcryptVersion = "0.10.2"
val lombokVersion = "1.18.46"
val jwtVersion = "4.5.2"
val okhttpVersion = "5.4.0"
val gsonVersion = "2.14.0"
val graphqlScalarsVersion = "24.0"
val commonsIoVersion = "2.22.0"
val time4jVersion = "5.9"
val testcontainersVersion = "1.21.4"

dependencies {
    // Quarkus BOM — transitively manages versions for every io.quarkus:* and many third-party libs
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))

    // Quarkus Core
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-vertx")

    // REST & JSON
    implementation("io.quarkus:quarkus-jackson")

    // GraphQL
    implementation("io.quarkus:quarkus-smallrye-graphql")

    // Database (Reactive MySQL)
    implementation("io.quarkus:quarkus-reactive-mysql-client")

    // Redis
    implementation("io.quarkus:quarkus-redis-client")

    // Auth0 JWT (manual token creation/validation — we do NOT use quarkus-smallrye-jwt
    // because the project implements its own JWT auth flow via JWTAuthProvider and
    // GraphQLAuthContext, which extract/validate Bearer tokens manually per request)
    implementation("com.auth0:java-jwt:$jwtVersion")

    // BCrypt
    implementation("at.favre.lib:bcrypt:$bcryptVersion")

    // Vert.x Web Client (Mutiny) for LLM/ComfyUI HTTP calls
    implementation("io.smallrye.reactive:smallrye-mutiny-vertx-web-client")

    // GraphQL Java Extended Scalars
    implementation("com.graphql-java:graphql-java-extended-scalars:$graphqlScalarsVersion")

    // Health
    implementation("io.quarkus:quarkus-smallrye-health")

    // Metrics
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")

    // WebSockets
    implementation("io.quarkus:quarkus-websockets-next")

    // Scheduler
    implementation("io.quarkus:quarkus-scheduler")

    // Logging (Quarkus uses JBoss Logging by default, SLF4J bridge included)
    implementation("org.jboss.slf4j:slf4j-jboss-logmanager")

    // Lombok
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")

    // Apache Commons
    implementation("org.apache.commons:commons-lang3")
    implementation("commons-io:commons-io:$commonsIoVersion")

    // HTTP Client for LLM
    implementation("com.squareup.okhttp3:okhttp:$okhttpVersion")
    implementation("com.squareup.okhttp3:okhttp-sse:$okhttpVersion")

    // JSON Processing
    implementation("com.google.code.gson:gson:$gsonVersion")

    // Time4J (full replacement for java.time)
    implementation("net.time4j:time4j-base:$time4jVersion")
    implementation("net.time4j:time4j-sqlxml:$time4jVersion")

    // Mutiny Reactor bridge (for converting existing Flux subscriptions)
    implementation("io.smallrye.reactive:mutiny-reactor")

    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.testcontainers:testcontainers:$testcontainersVersion")
    testImplementation("org.testcontainers:mysql:$testcontainersVersion")
}

group = "com.eonmux"
version = "3.0.0"
description = "Reactive GraphQL backend for Veyloria using Quarkus"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<Test>().configureEach {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
