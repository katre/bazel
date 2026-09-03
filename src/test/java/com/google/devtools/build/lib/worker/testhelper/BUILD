load("@rules_java//java:defs.bzl", "java_binary", "java_library", "java_test")

package(
    default_applicable_licenses = ["//:license"],
    default_testonly = 1,
    default_visibility = ["//src:__subpackages__"],
)

filegroup(
    name = "srcs",
    srcs = glob(["**"]),
)

# Abstract base class for test workers
java_library(
    name = "WorkerBase",
    srcs = ["WorkerBase.java"],
    deps = [
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/worker:work_request_handlers",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party/java/gson",
        "//third_party/java/guava:base",
        "//third_party/java/guava:collect",
    ],
)

# ExampleWorker binary
java_library(
    name = "ExampleWorker_lib",
    srcs = [
        "ExampleWorker.java",
        "ExampleWorkerOptions.java",
    ],
    deps = [
        ":WorkerBase",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/worker:work_request_handlers",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party/java/gson",
        "//third_party/java/guava:base",
        "//third_party/java/guava:collect",
    ],
)

java_binary(
    name = "ExampleWorker",
    main_class = "com.google.devtools.build.lib.worker.testhelper.ExampleWorker",
    visibility = [
        "//src/test/shell/integration:__pkg__",
    ],
    runtime_deps = [":ExampleWorker_lib"],
)

# ExampleWorkerMultiplexer binary
java_library(
    name = "ExampleWorkerMultiplexer_lib",
    srcs = [
        "ExampleWorkerMultiplexer.java",
        "ExampleWorkerMultiplexerOptions.java",
    ],
    deps = [
        ":WorkerBase",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/worker:work_request_handlers",
        "//src/main/java/com/google/devtools/common/options",
        "//src/main/protobuf:worker_protocol_java_proto",
        "//third_party/java/gson",
        "//third_party/java/guava:base",
        "//third_party/java/guava:collect",
    ],
)

java_binary(
    name = "ExampleWorkerMultiplexer",
    main_class = "com.google.devtools.build.lib.worker.testhelper.ExampleWorkerMultiplexer",
    visibility = [
        "//src/test/shell/integration:__pkg__",
    ],
    runtime_deps = [":ExampleWorkerMultiplexer_lib"],
)

# PersistentTestWorker binary
java_library(
    name = "PersistentTestWorker_lib",
    srcs = ["PersistentTestWorker.java"],
    deps = [
        ":WorkerBase",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/worker:work_request_handlers",
        "//src/main/java/com/google/devtools/common/options",
    ],
)

java_binary(
    name = "PersistentTestWorker",
    main_class = "com.google.devtools.build.lib.worker.testhelper.PersistentTestWorker",
    visibility = [
        "//src/test/shell/integration:__pkg__",
    ],
    runtime_deps = [":PersistentTestWorker_lib"],
)

# Unit tests for WorkerBase
java_test(
    name = "WorkerBaseTest",
    size = "small",
    srcs = ["WorkerBaseTest.java"],
    deps = [
        ":WorkerBase",
        "//src/main/java/com/google/devtools/build/lib/actions:execution_requirements",
        "//src/main/java/com/google/devtools/build/lib/worker:work_request_handlers",
        "//src/main/java/com/google/devtools/common/options",
        "//third_party/java/guava:collect",
        "//third_party/java/junit",
        "//third_party/java/truth",
    ],
)
