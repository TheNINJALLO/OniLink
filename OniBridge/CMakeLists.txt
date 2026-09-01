cmake_minimum_required(VERSION 3.24)
if(NOT DEFINED ONIBRIDGE_VERSION)
    set(ONIBRIDGE_VERSION "0.3.0-beta.6")
endif()
string(REGEX MATCH "^[0-9]+\\.[0-9]+\\.[0-9]+" ONIBRIDGE_PROJECT_VERSION "${ONIBRIDGE_VERSION}")
if(NOT ONIBRIDGE_PROJECT_VERSION)
    message(FATAL_ERROR "ONIBRIDGE_VERSION must begin with a numeric major.minor.patch version")
endif()
# CMake's project VERSION is numeric-only; release identifiers keep the full prerelease suffix.
project(OniBridge VERSION "${ONIBRIDGE_PROJECT_VERSION}" LANGUAGES CXX)

find_package(Threads REQUIRED)

option(ONIBRIDGE_BUILD_TESTS "Build OniBridge unit tests" ON)
option(ONIBRIDGE_BUILD_PLUGIN "Build the Endstone plugin adapter" OFF)
set(ONIBRIDGE_GENERATED_ADAPTER "" CACHE FILEPATH "Reviewed generated native login adapter source")
set(ONIBRIDGE_ENDSTONE_SOURCE "" CACHE PATH "Optional exact Endstone source tree for offline/reproducible plugin builds")

add_library(onibridge_core STATIC
    src/forwarding/crypto.cpp
    src/forwarding/token.cpp
    src/forwarding/login_envelope.cpp
    src/forwarding/replay_cache.cpp
    src/forwarding/trusted_proxy.cpp
    src/identity/verified_identity_registry.cpp
    src/runtime/onibridge_service.cpp
    src/hooks/common/direct_call_site_hook.cpp
    src/hooks/common/profile_authentication_adapter.cpp
    src/profiles/hook_profile.cpp
    src/config/onibridge_config.cpp
    src/commands/command_compatibility_monitor.cpp
    src/migration/legacy_data_migrator.cpp)
target_sources(onibridge_core PRIVATE src/control/control_server.cpp)
target_compile_features(onibridge_core PUBLIC cxx_std_20)
target_include_directories(onibridge_core PUBLIC include PRIVATE src/forwarding)
target_link_libraries(onibridge_core PUBLIC Threads::Threads)
set_target_properties(onibridge_core PROPERTIES POSITION_INDEPENDENT_CODE ON)
if(MSVC)
    target_compile_options(onibridge_core PRIVATE /W4 /permissive-)
    target_link_libraries(onibridge_core PUBLIC ws2_32)
else()
    target_compile_options(onibridge_core PRIVATE -Wall -Wextra -Wpedantic -Wconversion)
endif()

# Endstone's Linux ABI uses LLVM libc++. Keep the shared core and every target that consumes it on
# that same C++ runtime. Mixing clang's default libstdc++ into the static core leaves unresolved
# std::__cxx11 symbols in the final plugin even though the plugin target itself selects libc++.
if(CMAKE_SYSTEM_NAME STREQUAL "Linux" AND CMAKE_CXX_COMPILER_ID MATCHES "Clang")
    target_compile_options(onibridge_core PUBLIC -stdlib=libc++)
    target_link_options(onibridge_core INTERFACE -stdlib=libc++)
endif()

if(ONIBRIDGE_BUILD_TESTS)
    enable_testing()
    add_executable(onibridge_tests tests/unit/forwarding_tests.cpp)
    target_link_libraries(onibridge_tests PRIVATE onibridge_core)
    add_test(NAME onibridge_tests COMMAND onibridge_tests)
    add_executable(onibridge_hook_harness tests/hook-harness/hook_harness.cpp)
    target_link_libraries(onibridge_hook_harness PRIVATE onibridge_core)
    add_test(NAME onibridge_hook_harness COMMAND onibridge_hook_harness)
endif()

if(ONIBRIDGE_BUILD_PLUGIN)
    if(NOT ONIBRIDGE_GENERATED_ADAPTER OR NOT EXISTS "${ONIBRIDGE_GENERATED_ADAPTER}")
        message(FATAL_ERROR
            "A reviewed profile-specific ONIBRIDGE_GENERATED_ADAPTER is required; generic plugin artifacts are forbidden")
    endif()
    include(FetchContent)
    if(ONIBRIDGE_ENDSTONE_SOURCE)
        if(NOT EXISTS "${ONIBRIDGE_ENDSTONE_SOURCE}/include/CMakeLists.txt")
            message(FATAL_ERROR "ONIBRIDGE_ENDSTONE_SOURCE is not an Endstone source tree")
        endif()
        add_subdirectory("${ONIBRIDGE_ENDSTONE_SOURCE}/include" "${CMAKE_BINARY_DIR}/endstone-include")
    else()
        FetchContent_Declare(
            endstone_source
            GIT_REPOSITORY https://github.com/EndstoneMC/endstone.git
            GIT_TAG 8f84d6f5b556916597ed5b6b71329b2ed3ca8fc8
            GIT_SHALLOW FALSE)
        FetchContent_GetProperties(endstone_source)
        if(NOT endstone_source_POPULATED)
            FetchContent_Populate(endstone_source)
        endif()
        add_subdirectory("${endstone_source_SOURCE_DIR}/include" "${CMAKE_BINARY_DIR}/endstone-include")
    endif()
    endstone_add_plugin(onibridge src/plugin/onibridge_plugin.cpp "${ONIBRIDGE_GENERATED_ADAPTER}")
    target_compile_features(onibridge PRIVATE cxx_std_20)
    target_compile_definitions(onibridge PRIVATE ONIBRIDGE_VERSION="${ONIBRIDGE_VERSION}")

    # The reviewed generated adapter is the single source of truth for its exact BDS identity.
    # Extracting these diagnostic values prevents a new profile from being paired with stale
    # hardcoded control-plane metadata while the adapter still performs the runtime hash check.
    file(READ "${ONIBRIDGE_GENERATED_ADAPTER}" ONIBRIDGE_ADAPTER_SOURCE)
    string(REGEX MATCH
        "kProfileId\\[\\] = \"bds-([0-9]+\\.[0-9]+\\.[0-9]+(\\.[0-9]+)?)-(linux|windows)-x86_64-[0-9a-f]+\""
        ONIBRIDGE_PROFILE_MATCH "${ONIBRIDGE_ADAPTER_SOURCE}")
    set(ONIBRIDGE_PROFILE_BDS_VERSION "${CMAKE_MATCH_1}")
    set(ONIBRIDGE_PROFILE_OPERATING_SYSTEM "${CMAKE_MATCH_3}")
    if(NOT ONIBRIDGE_PROFILE_MATCH)
        message(FATAL_ERROR "The generated adapter does not contain a valid exact BDS profile ID")
    endif()
    string(REGEX MATCH
        "kExecutableHash\\[\\] = \"([0-9a-f]+)\""
        ONIBRIDGE_HASH_MATCH "${ONIBRIDGE_ADAPTER_SOURCE}")
    set(ONIBRIDGE_PROFILE_BDS_SHA256 "${CMAKE_MATCH_1}")
    string(LENGTH "${ONIBRIDGE_PROFILE_BDS_SHA256}" ONIBRIDGE_PROFILE_HASH_LENGTH)
    if(NOT ONIBRIDGE_HASH_MATCH OR NOT ONIBRIDGE_PROFILE_HASH_LENGTH EQUAL 64)
        message(FATAL_ERROR "The generated adapter does not contain a valid 64-character BDS SHA-256")
    endif()
    if(WIN32)
        set(ONIBRIDGE_TARGET_OPERATING_SYSTEM "windows")
    else()
        set(ONIBRIDGE_TARGET_OPERATING_SYSTEM "linux")
    endif()
    if(NOT ONIBRIDGE_PROFILE_OPERATING_SYSTEM STREQUAL ONIBRIDGE_TARGET_OPERATING_SYSTEM)
        message(FATAL_ERROR
            "The ${ONIBRIDGE_PROFILE_OPERATING_SYSTEM} generated adapter cannot build for ${ONIBRIDGE_TARGET_OPERATING_SYSTEM}")
    endif()
    target_compile_definitions(onibridge PRIVATE
        ONIBRIDGE_BDS_VERSION="${ONIBRIDGE_PROFILE_BDS_VERSION}"
        ONIBRIDGE_BDS_SHA256="${ONIBRIDGE_PROFILE_BDS_SHA256}"
        ONIBRIDGE_OPERATING_SYSTEM="${ONIBRIDGE_TARGET_OPERATING_SYSTEM}")
    target_link_libraries(onibridge PRIVATE onibridge_core)
    set_target_properties(onibridge PROPERTIES PREFIX "" OUTPUT_NAME "onibridge")
endif()
