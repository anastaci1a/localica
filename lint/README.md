# UI policy lint foundation

This JVM tooling module provides fourteen ERROR rules and paired source fixtures. The application loads it through `lintChecks`. Existing incidents are frozen in the generated app baseline; the immutable comparison rejects added identities and equal-count substitutions.

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

`GlassPopupWithoutAnchor` (#748) flags a call to a function the app marks `@GlassPopupOpener` that sits in none of the origin scopes: `GlassPopupFrom`, `GlassUnanchoredPopup`, `GlassPopupInherited`, a `CompositionLocalProvider` supplying `LocalGlassOrigin`, the body of another marked opener, or a `GlassPlacement.Page` call. Symbols resolve through imports and aliases; an unmarked wrapper is flagged where it calls the opener.

The API and test infrastructure are pinned to the lint version matching AGP. Compatibility is claimed only for that tested API. The tooling jar embeds its safe YAML parser; neither dependency belongs on an APK runtime classpath.

Escape validation fails closed without exact declarations and frozen expiry inputs. Activation supplies the `knownSteps`, `completedSteps` and `release` lint options from reviewed stage metadata. A manifest's reviewer field does not establish that a human review happened; integration owns that evidence.

Rollback the complete activation before adopting production callers. Never disable an individual rule to conceal a violation.
