# Contributing to OniLink

Keep changes focused, test the path you touched, and preserve OniLink's fail-closed defaults. Native
authentication, forwarding claims, trusted proxy checks, replay protection, and dashboard credential
handling all need tests when their behavior changes.

## Source boundaries

Project-owned code lives in `OniLink/src`, `OniBridge/src`, `OniBridge/include`, `tools`, and
`scripts`. Do not hand-edit vendored Cloudburst code under
`OniLink/protocol` or `OniLink/network`. Files under `OniBridge/generated` come from the locked BDS
profile pipeline and must be regenerated from their inputs.

Comments should explain a protocol rule, safety boundary, or non-obvious tradeoff. Put investigation
timelines and capture notes in an issue or engineering document instead of leaving them in a runtime
class.

## Formatting

- C++ uses clang-format 18 and the root `.clang-format` file.
- Python uses Ruff 0.15.7.
- Dashboard HTML, CSS, and JavaScript use Prettier 3.6.2.
- Other text files follow `.editorconfig`.

The CI style job checks all three formatters. To run the portable checks locally:

```bash
ruff format --check tools OniBridge/tools/bdsctl OniBridge/tools/sdkgen
npx --yes prettier@3.6.2 --check \
  OniLink/src/main/resources/dashboard/index.html \
  OniLink/src/main/resources/dashboard/styles.css \
  OniLink/src/main/resources/dashboard/app.js
find OniBridge/src OniBridge/include OniBridge/tests -type f \
  \( -name '*.cpp' -o -name '*.hpp' \) -print0 \
  | xargs -0 clang-format-18 --dry-run --Werror --style=file
```

## Tests

Run the complete local suite before opening a pull request:

```bash
./OniLink/gradlew -p OniLink test standaloneJar --no-daemon
cmake -S OniBridge -B OniBridge/build/test \
  -DONIBRIDGE_BUILD_PLUGIN=OFF \
  -DONIBRIDGE_BUILD_TESTS=ON
cmake --build OniBridge/build/test --config Release
ctest --test-dir OniBridge/build/test -C Release --output-on-failure
(cd OniBridge/tools/bdsctl && python -m unittest discover -s tests -v)
(cd OniBridge/tools/sdkgen && python -m unittest discover -s tests -v)
(cd tools && python -m unittest \
  test_package_release test_package_linux test_pterodactyl_egg test_linux_abi -v)
```

Never commit BDS archives, server executables, real forwarding secrets, production addresses, player
identifiers, dashboard credentials, or complete forwarding tokens.
