# PersistentTestInfo Refactoring Summary

## Problem

PersistentTestInfo needed to use FilesToRunProvider type, but there was a circular dependency:
- `PersistentTestInfo` was in the separate `test/persistent_test_info` BUILD target
- `FilesToRunProvider` was in `analysis_cluster`
- `analysis_cluster` already depended on `test/persistent_test_info`
- This prevented `test/persistent_test_info` from depending on `analysis_cluster`

## Previous Workaround (Option 1)

Stored `FilesToRunProvider` as `Object` type, requiring manual casts:
```java
@Nullable private final Object workerExecutable;  // FilesToRunProvider

// Usage required casting
FilesToRunProvider workerExec = (FilesToRunProvider) persistentTestInfo.getWorkerExecutable();
```

**Issues:**
- ❌ Lost compile-time type safety
- ❌ Required manual casts in multiple places
- ❌ Runtime ClassCastException risk

## Solution Implemented (Option 3)

**Move PersistentTestInfo.java into analysis_cluster**

Since PersistentTestInfo is part of the analysis infrastructure and is tightly coupled with test execution, moving it into `analysis_cluster` makes architectural sense.

### Changes Made

1. **src/main/java/com/google/devtools/build/lib/analysis/BUILD**
   - Added `test/PersistentTestInfo.java` to `analysis_cluster` srcs
   - Removed dependency on `:test/persistent_test_info` from analysis_cluster deps
   - Deleted the separate `test/persistent_test_info` java_library target

2. **src/main/java/com/google/devtools/build/lib/exec/BUILD**
   - Removed dependency on `:test/persistent_test_info` from `standalone_test_strategy`
   - (Already had dependency on `analysis_cluster`, so no addition needed)

3. **src/main/java/com/google/devtools/build/lib/analysis/test/PersistentTestInfo.java**
   - Added import: `import com.google.devtools.build.lib.analysis.FilesToRunProvider;`
   - Changed field type from `Object` to `FilesToRunProvider`
   - Updated constructor parameter from `Object` to `FilesToRunProvider`
   - Updated getter return type from `Object` to `FilesToRunProvider`
   - Added proper type validation in Starlark constructor

4. **src/main/java/com/google/devtools/build/lib/exec/StandaloneTestStrategy.java**
   - Removed manual cast: `(FilesToRunProvider)` no longer needed
   - Direct assignment: `FilesToRunProvider workerExec = persistentTestInfo.getWorkerExecutable();`

## Benefits

✅ **Full compile-time type safety** - No Object type, no casts
✅ **Cleaner code** - Direct type usage without workarounds
✅ **No circular dependency** - PersistentTestInfo is now part of analysis_cluster
✅ **Proper architecture** - Test analysis providers belong in the analysis cluster
✅ **Validation at Starlark level** - Type checking in constructor catches errors early

## Build Status

✅ `bazel build //src:bazel-dev` - **SUCCESS**

## Why This Works

The circular dependency was artificial:
- `PersistentTestInfo` is fundamentally part of the test analysis phase
- It provides metadata about how tests should be executed
- It belongs architecturally with other test analysis infrastructure
- Being in `analysis_cluster` allows it to use all analysis-phase types directly

## Architecture After Change

```
analysis_cluster
├── FilesToRunProvider.java
├── RunfilesSupport.java
├── test/PersistentTestInfo.java  ← Now here
├── test/TestRunnerAction.java
└── ... (other analysis infrastructure)

exec (standalone_test_strategy)
└── depends on analysis_cluster only
```

No circular dependencies, clean type usage, proper architectural placement.
