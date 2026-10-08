plugins { java; application }
repositories { mavenCentral() }
dependencies { implementation("cn.enaium.webrtc.aec3:webrtc-aec3-kmp-jvm:1.0.3") }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
application { mainClass.set("Aec3Offline") }
tasks.register("listJars") { doLast { configurations.runtimeClasspath.get().forEach { println(it) } } }
