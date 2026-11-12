# UI policy lint foundation

This JVM tooling module provides eighteen ERROR rules and paired source fixtures. The application loads it through `lintChecks`. Existing incidents are frozen in the generated app baseline; the immutable comparison rejects added identities and equal-count substitutions.

Build and test with the repository's JDK 17 toolchain:

```sh
./gradlew :lint:test :lint:jar --no-daemon --max-workers=1
```

The tests inspect the built service metadata and load its registry through lint. The standalone consumer fixture also exercises Gradle `lintChecks` discovery:

```sh
./gradlew -p lint/src/test/fixtures/consumer lint \
  -Pscenario=positive -PregistryJar="$PWD/lint/build/libs/lint.jar" \
  -PsmokeOutput="$PWD/build/lint-consumer-positive"
```

Repeat with `scenario=negative` and a separate output directory. That invocation must fail with `RawUiMotion` at `Screen.kt:2`. Neither fixture is application source.

The API and test infrastructure are pinned to the lint version matching AGP. Compatibility is claimed only for that tested API. The tooling jar embeds its safe YAML parser; neither dependency belongs on an APK runtime classpath.

Escape validation fails closed without exact declarations and frozen expiry inputs. Activation supplies the `knownSteps`, `completedSteps` and `release` lint options from reviewed stage metadata. A manifest's reviewer field does not establish that a human review happened; integration owns that evidence.

Rollback the complete activation before adopting production callers. Never disable an individual rule to conceal a violation.

Popup conformity is a zero-finding release gate. Screen popup homes, motion overrides, private triggers, private draw paths and duplicate popup roles cannot enter the baseline. The exact shared owners are pinned in `src/main/resources/popup-owners.txt`, including the existing components, engine and three legacy popup files. New neighboring files receive no ownership automatically. `popup-roles.txt` pins the existing home and trigger declarations; adding a second implementation fails even inside an existing owner.

Shell hosts have exact factory and presenter counts in `popup-hosts.txt`. LocalicaScreen retains three shared homes; PlayerSurface and QueueSurface each retain one fallback. Theme presents the root without creating another home. Reintroducing a utility home exceeds the gate. Foreign modules never inherit application owner paths.
