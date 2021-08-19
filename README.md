# android-media-streaming
Streams `HLS`, `TS` and `AAC` content and uses Android `MediaCodec` for hardware decoding of video and audio streams.
Built around Kotlin `Flow` and `Ktor` for handling data flows.

## This project is in an early development stage!
Currently, this project is just a proof of concept. Things such as
* Buffering
* Audio-video synchronization
* Seeking
* playback controls

are still missing. See the open issues for more information.

## Usage
This is an Android Studio project. It has been developed using `Android Studio Arctic Fox` currently at version `2020.3.1`.

### Building and running
To build and run the project on an Android device or emulator, either use Android Studio or run
```sh
./gradlew installDebug
```

### Building and testing
To run tests, either use Android Studio or run
```sh
./gradlew testDebugUnitTest
```
for unit test and

```sh
./gradlew connectedAndroidTest
```
for integration tests on a connected Android device or emulator.

## Licence
This project is released under the Apache-2.0 License
