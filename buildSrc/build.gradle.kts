plugins { java }

tasks.register<JavaExec>("verifyDesktopTarget") {
    dependsOn(tasks.named("testClasses"))
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.music.bitchord.build.DesktopTargetTest")
}
