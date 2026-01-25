plugins {
    id("org.openrewrite.build.recipe-library") version "latest.release"
    id("org.openrewrite.build.moderne-source-available-license") version "latest.release"
}

group = "org.openrewrite.recipe"
description = "Trusted context for LLM coding agents"

val rewriteVersion = rewriteRecipe.rewriteVersion.get()
dependencies {
    implementation(platform("org.openrewrite:rewrite-bom:$rewriteVersion"))
    implementation(platform("org.openrewrite.recipe:rewrite-recipe-bom:$rewriteVersion"))
    implementation("org.openrewrite:rewrite-java")
    implementation("org.openrewrite:rewrite-properties")
    implementation("org.openrewrite:rewrite-yaml")
    implementation("org.openrewrite:rewrite-maven")

    implementation("com.univocity:univocity-parsers:2.9.1")

    runtimeOnly("org.openrewrite:rewrite-java-17")

    testImplementation("org.openrewrite:rewrite-test")
    testImplementation("org.openrewrite:rewrite-java-test")

    testRuntimeOnly("ch.qos.logback:logback-classic:latest.release")
}
