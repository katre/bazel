# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

This is the **Bazel** repository - a fast, scalable build and test system that supports multiple languages (Java, C++, Go, Python, etc.). The codebase is primarily Java with C++ for the client, Starlark for build rules, and shell scripts for testing.

## Building Bazel

### From Scratch (Bootstrap)
```bash
./compile.sh
# Output: output/bazel
```

This script bootstraps Bazel without requiring an existing Bazel installation. It compiles the Java sources, then uses that binary to build the final Bazel binary.

### With Existing Bazel (Faster Iteration)
```bash
# Development build (faster, for iteration)
bazel build //src:bazel-dev

# Standard build
bazel build //src:bazel

# With Remote Build Execution (if available)
bazel build --config=remote //src:bazel-dev
```

### Testing Changes Safely
To test changes without affecting your workspace:
```bash
# Build and copy to tmp
bazel build //src:bazel-dev && cp bazel-bin/src/bazel-dev /tmp/bazel

# Use with isolated output base
/tmp/bazel --output_base=/tmp/ob-dev <command>
```

## Testing

### Running Tests
```bash
# Run all tests in a package
bazel test //src/test/java/com/google/devtools/build/lib/...

# Run specific test
bazel test //src/test/java/com/google/devtools/build/lib/analysis:AnalysisTest

# Run with filter for specific test methods (Java tests)
bazel test //src/test/java/com/google/devtools/build/lib/analysis:AnalysisTest --test_filter=MyTest.testMethod

# Run shell integration tests
bazel test //src/test/shell/integration/...
```

### Finding Relevant Tests
To find tests that depend on a modified file:
```bash
bazel query "rdeps(//src/test/..., //path/to:target)"
```

### Test Types
- **Unit Tests**: Java tests in `src/test/java/` (using JUnit)
- **Integration Tests**:
  - Java-based: Subclasses of `BuildIntegrationTestCase`
  - Shell-based: In `src/test/shell/` using bash test framework

## Architecture

### Client/Server Model
- **Client** (`src/main/cpp/`): Lightweight C++ wrapper that starts and communicates with the server
- **Server** (`src/main/java/`): Long-lived Java process that performs builds

### Core Components

**Skyframe** (`src/main/java/com/google/devtools/build/skyframe/`):
- Incremental evaluation framework at the heart of Bazel
- Computation is modeled as `SkyFunction`s that evaluate `SkyKey`s into `SkyValue`s
- Provides automatic incrementality and parallelism

**Main Library** (`src/main/java/com/google/devtools/build/lib/`):
- `actions/`: Action execution system
- `analysis/`: Build graph analysis phase
- `packages/`: Package and target loading
- `rules/`: Built-in language rules (Java, C++, Python, etc.)
- `exec/`: Execution strategies (local, remote, worker)
- `worker/`: Persistent worker infrastructure
- `skyframe/`: Bazel-specific Skyframe functions
- `bazel/`: Bazel-specific (vs Blaze) code

**Starlark** (`src/main/java/net/starlark/`):
- The configuration language interpreter for BUILD and .bzl files
- `builtins_bzl/`: Built-in Starlark rules

### Build Phases
1. **Loading**: Parse BUILD files and .bzl files
2. **Analysis**: Construct action graph from configured targets
3. **Execution**: Run actions to produce outputs

## Code Organization

### Source Structure
```
src/
├── main/
│   ├── cpp/           # C++ client code
│   ├── java/
│   │   ├── com/google/devtools/build/
│   │   │   ├── lib/           # Main Bazel implementation
│   │   │   └── skyframe/      # Skyframe framework
│   │   └── net/starlark/      # Starlark language
│   ├── native/        # Native code (JNI)
│   ├── protobuf/      # Protocol buffer definitions
│   └── starlark/      # Starlark built-in rules
├── test/             # Tests mirror src/main structure
│   ├── java/         # Java unit/integration tests
│   └── shell/        # Shell integration tests
└── tools/            # Build tools (singlejar, launcher, etc.)
```

### Configuration
- **MODULE.bazel**: Bzlmod module dependencies (replaces WORKSPACE)
- **.bazelrc**: Shared build configurations (remote execution, CI configs, etc.)
- **BUILD** files: Define build targets throughout the codebase

## Development Workflow

### Making Changes

1. **Find relevant code**: Main implementation is in `src/main/java/com/google/devtools/build/lib/`
2. **Build**: Use `bazel build //src:bazel-dev` for fast iteration
3. **Test**:
   - Write/update tests in `src/test/java/` mirroring the source structure
   - Run relevant tests with `bazel test`
4. **Format code**: Java follows Google Java Style
5. **Commit**: Follow project conventions (see CONTRIBUTING.md)

### Common Patterns

**Adding a new Starlark provider**:
1. Create API interface in `src/main/java/com/google/devtools/build/lib/starlarkbuildapi/`
2. Implement in `src/main/java/com/google/devtools/build/lib/analysis/`
3. Register in `StarlarkGlobalsImpl.java`
4. Add tests in `src/test/java/`

**Adding persistent worker support**:
- See `src/main/java/com/google/devtools/build/lib/worker/` for worker infrastructure
- Workers use `WorkRequest`/`WorkResponse` protocol (proto or JSON)
- Test actions can configure `ExecutionRequirements.SUPPORTS_WORKERS`

## Current Projects

### Persistent Test Runners (In Progress)
See `ptr_design.md` and `ptr_plan.md` for the design and implementation plan. This adds support for tests to run in persistent worker processes, avoiding startup overhead for JVM tests and simulators.

Key files:
- Design: `ptr_design.md`
- Implementation plan: `ptr_plan.md`
- Provider: `src/main/java/com/google/devtools/build/lib/analysis/test/PersistentTestInfo.java` (to be created)
- Flag: `TestConfiguration.java` `--enable_persistent_test_runners`
- Integration: `TestRunnerAction.java`, `StandaloneTestStrategy.java`, `WorkerSpawnRunner.java`

## Important Commands

```bash
# Build Bazel for development
bazel build //src:bazel-dev

# Run tests
bazel test //src/test/...

# Query for test dependencies
bazel query "rdeps(//src/test/..., //src/main/java/...)"

# Format Starlark files (if buildifier is installed)
buildifier -r .

# Update module lockfile
bazel mod deps --lockfile_mode=update

# Check for issues with bazelrc configs
bazel info --announce_rc
```

## Tips

- Use `--output_base` to isolate test builds from your working environment
- The `--config=remote` flag enables Remote Build Execution if you have access
- Java language level is 21, runtime is Java 25 (see .bazelrc)
- Test failures often require looking at test logs: `bazel test --test_output=all` or check `bazel-testlogs/`
- For Starlark debugging, use `print()` statements in .bzl files and run with `--subcommands` or check console output
