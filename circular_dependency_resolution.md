# Circular Dependency Resolution: FilesToRunProvider and PersistentTestInfo

## Problem

When implementing persistent test runner support, we encountered a circular dependency:

```
analysis_cluster → test/persistent_test_info → (wants to use) → FilesToRunProvider → analysis_cluster
```

- `PersistentTestInfo` (in `test/persistent_test_info` target) needs `FilesToRunProvider` type
- `FilesToRunProvider` is in the `analysis_cluster` target
- `analysis_cluster` already depends on `test/persistent_test_info` (transitively)
- Adding `analysis_cluster` as a dependency of `test/persistent_test_info` creates a cycle

## Solutions Considered

### Option 1: Use Object Type (IMPLEMENTED)

**Approach:** Store `FilesToRunProvider` as `Object` in `PersistentTestInfo`, requiring manual casts by callers.

**Implementation:**
```java
// In PersistentTestInfo.java
@Nullable private final Object workerExecutable;  // FilesToRunProvider stored as Object

// Callers cast manually
FilesToRunProvider workerExec = (FilesToRunProvider) persistentTestInfo.getWorkerExecutable();
```

**Pros:**
- ✅ No circular dependency
- ✅ Simple solution with minimal changes
- ✅ Build passes successfully
- ✅ No risk to existing code

**Cons:**
- ❌ Loses compile-time type safety
- ❌ Runtime ClassCastException possible if used incorrectly
- ❌ Less clear API (developers see Object instead of FilesToRunProvider)
- ❌ Requires documentation explaining the cast

**Status:** ✅ **IMPLEMENTED AND WORKING**

### Option 2: Extract FilesToRunProvider to Separate Target (ATTEMPTED)

**Approach:** Create a new `:files_to_run_provider` BUILD target containing `FilesToRunProvider.java`, allowing both `analysis_cluster` and `test/persistent_test_info` to depend on it.

**Attempted Implementation:**
1. Created new BUILD target `files_to_run_provider`
2. Removed `FilesToRunProvider.java` from `analysis_cluster` srcs
3. Added `:files_to_run_provider` to both `analysis_cluster` and `test/persistent_test_info` deps

**Result:** ❌ **FAILED - Complex Dependencies**

**Why it failed:**
1. `FilesToRunProvider` depends on `RunfilesSupport` (same package)
2. `RunfilesSupport` has heavy dependencies:
   - `Runfiles`, `RuleContext`, `ActionConstructionContext`
   - `TransitiveInfoCollection`, `RunEnvironmentInfo`
   - `SourceManifestAction`, `SymlinkTreeAction`
   - Config fragments, build configuration, test infrastructure
3. Extracting both `FilesToRunProvider` and `RunfilesSupport` together still failed due to their dependencies on other `analysis_cluster` classes
4. The dependency web is too tightly coupled to extract cleanly

## Decision

**Use Option 1 (Object Type)** - This is the pragmatic solution that:
- Works immediately without complex refactoring
- Maintains the existing architecture
- Adds minimal complexity (just a cast in a few locations)
- Is well-documented with comments explaining the circular dependency avoidance

## Files Modified

### Using Option 1 (Current Implementation)

1. **PersistentTestInfo.java**
   - Stores `workerExecutable` as `Object` instead of `FilesToRunProvider`
   - Comments explain the circular dependency reason
   - Accessor returns `Object` requiring callers to cast

2. **StandaloneTestStrategy.java**
   - Casts `workerExecutable` to `FilesToRunProvider` when using it
   - Comment explains the cast is safe (validated at Starlark level)

3. **PersistentTestInfoApi.java**
   - Already uses `Object` type in Starlark API (for flexibility)

## Build Status

✅ `bazel build //src:bazel-dev` - **SUCCESS**

## Conclusion

While extracting `FilesToRunProvider` into a separate target would provide better type safety, the complex web of dependencies in the analysis phase makes this impractical. The `Object` type approach is a reasonable trade-off that:

- Solves the circular dependency problem
- Maintains build correctness
- Documents the reason for the unusual typing
- Can be refactored later if the analysis cluster is further split (as indicated by the TODO at line 20 of the BUILD file)

The loss of compile-time type safety for this one field is acceptable given:
1. The type is validated at the Starlark rule level before reaching the provider
2. Only a few callers need to perform the cast
3. The alternative would require significant refactoring of core analysis infrastructure
