# more-executors

[![Build Status](https://travis-ci.org/Adeynack/more-executors.svg?branch=master)](https://travis-ci.org/Adeynack/more-executors)

Specialised executors for the JVM (mainly Java and Kotlin, but also Scala)

## Local deployment

Deploying to the local Maven repository (on the development machine) allows other projects to use the library in its
development state. It is suggested, to avoid confusion, to append `-SNAPSHOT` to the version in the [build.gradle](build.gradle) file.

```bash
./gradlew publishToMavenLocal
```

That also allows quick development of production software while adding and debugging features to `more-executors` locally.

Project using this library from local publishing need to include this in their Gradle file:

```groovy
repositories {
    mavenLocal()
}
dependencies {
    compile "com.github.adeynack:more-executors:0.1-SNAPSHOT"
}
```
