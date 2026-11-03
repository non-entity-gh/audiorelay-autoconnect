# Tests

- `ArgsTest.java`: option parsing/validation. Runs in CI.
Compile first: `./build.sh && javac -cp build/classes -d build/test test/ScanTest.java && javac -cp /path/to/audiorelay.jar -d build/test test/E2E.java`

- `ScanTest.java`: prints what the scanner finds in a real `audiorelay.jar`:
  `java --add-exports java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED -cp build/classes:build/test ScanTest /path/to/audiorelay.jar`
- `E2E.java`: loads a real `audiorelay.jar`, attaches the agent, drives the real Player state machine (connect, drop, fast
  manual disconnect, server-ended sessions) and checks the agent's reactions. Needs your own copy of AudioRelay (not included in this repo):
  `java -cp build/test:/path/to/audiorelay.jar -javaagent:dist/audiorelay-autoconnect.jar=host=192.168.1.50,interval=2,delay=1 E2E /path/to/audiorelay.jar`
