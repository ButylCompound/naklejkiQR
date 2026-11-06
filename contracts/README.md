# Generated QR examples

`generated-labels.json` contains payloads shared by the Python label generator tests and Android reader tests. Rows with `generator` arguments must match the exact QR text produced by `generate_pdf` with the fixed `labelId`. Other rows cover older three- and four-field labels. Expected reader fields include normalized units and timestamps, the separate display-only ID, and the optional lot number.

Run the generator tests from `GeneratorNaklejek` with `python -m unittest discover -s tests`, and Android tests from `AndroidApp` with `gradlew.bat :app:testDebugUnitTest`.
