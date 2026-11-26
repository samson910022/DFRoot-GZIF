# ko_pin_policy.cmake -- shared SHA-256 pin validation for both KO verifiers.
#
# Included by:
#   verify_ko_sha256.cmake    (the source-tree .ko embedded at compile time)
#   verify_packaged_ko.cmake  (the .dfroot_ko section inside libdfroot.so)
#
# Both verifiers receive the SAME KO_EXPECTED_SHA256S list from
# app/src/main/jni/CMakeLists.txt. This module is policy only: it normalizes and
# validates the supplied list, it never adds, relaxes, generates or accepts a
# new pin. Reviewing a new artifact still requires a deliberate edit of the
# pins in CMakeLists.txt plus docs.

# dfroot_validate_ko_pins(<raw_list> <caller_label> <out_var>)
#   Normalizes every entry to lowercase and fails closed on a malformed entry.
#   CMake's MATCHES has no interval quantifier ({64}), so length and forbidden
#   characters are checked separately. The caller's label is used only in the
#   error message so each verifier reports its own identity.
function(dfroot_validate_ko_pins raw_pins caller_label out_var)
    set(normalized "")
    foreach(raw IN LISTS raw_pins)
        string(STRIP "${raw}" pin)
        string(TOLOWER "${pin}" pin)
        string(LENGTH "${pin}" pin_len)
        if(NOT pin_len EQUAL 64 OR pin MATCHES "[^0-9a-f]")
            message(FATAL_ERROR
                "${caller_label}: KO_EXPECTED_SHA256S contains an invalid "
                "SHA-256 pin (expected exactly 64 hex digits): '${pin}'")
        endif()
        list(APPEND normalized "${pin}")
    endforeach()
    set(${out_var} "${normalized}" PARENT_SCOPE)
endfunction()
