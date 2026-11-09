//! NetBird Edition: JNI entry points for a tunnel run by the NetBird engine.
//! Separate from `jni_bindings.rs` so upstream updates merge without conflict.

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jint, jlong};
use jni::EnvUnowned;

use crate::jni_bindings::{get_string, throw, JavaCallback, JavaDnsSink};

/// Loads the split-tunnel prefixes of a tunnel run by another engine
/// (NetBird) from a `;`-separated list, and returns how many were loaded. An
/// invalid list clears them (fail-closed full tunnel) and throws.
#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_Wgbridge_setPolicyAllowedIps(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    prefixes: JString,
) -> jint {
    with_native_env!(unowned_env, env, {
        let list = get_string(env, &prefixes).unwrap_or_default();
        match crate::netbird::set_allowed_ips_from_list(&list) {
            Ok(n) => n as jint,
            Err(e) => {
                throw(env, &e);
                -1
            }
        }
    })
}

/// DNS policy for inbound packets of a tunnel not run by gotatun (the NetBird
/// engine): the same inspector `TunFdSend` applies, kept per tunnel because
/// TCP rewriting carries framing state across segments.
struct PacketDnsInspector {
    inspector: std::sync::Mutex<crate::dns::DnsInspector>,
    sink: JavaDnsSink,
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_DnsPacketInspector_nativeNew(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    dns_recorder: JObject,
) -> jlong {
    with_native_env!(unowned_env, env, {
        let Some(cb) = JavaCallback::new(env, dns_recorder) else {
            throw(env, "dnsRecorder must not be null");
            return 0;
        };
        let inspector = PacketDnsInspector {
            inspector: std::sync::Mutex::new(crate::dns::DnsInspector::default()),
            sink: JavaDnsSink(cb),
        };
        Box::into_raw(Box::new(inspector)) as jlong
    })
}

/// Returns the packet to write in place of `packet`, or null to keep it.
#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_DnsPacketInspector_nativeInspect(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    handle: jlong,
    packet: jni::objects::JByteArray,
) -> jni::sys::jbyteArray {
    with_native_env!(unowned_env, env, {
        if handle == 0 || packet.is_null() {
            return std::ptr::null_mut();
        }
        // SAFETY: handle is a Box::into_raw pointer from nativeNew, freed only
        // by nativeFree, which the Java side calls after the last inspect.
        let this = unsafe { &*(handle as *const PacketDnsInspector) };
        let Ok(original) = env.convert_byte_array(&packet) else {
            return std::ptr::null_mut();
        };
        let mut data = original.clone();
        let new_len = this
            .inspector
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
            .inspect_and_rewrite(&mut data, &this.sink);
        if let Some(n) = new_len {
            data.truncate(n);
        }
        if data == original {
            return std::ptr::null_mut();
        }
        env.byte_array_from_slice(&data)
            .map(|a| a.into_raw())
            .unwrap_or(std::ptr::null_mut())
    })
}

#[no_mangle]
pub extern "system" fn Java_net_kollnig_missioncontrol_wgbridge_DnsPacketInspector_nativeFree(
    mut unowned_env: EnvUnowned,
    _class: JClass,
    handle: jlong,
) {
    with_native_env!(unowned_env, _env, {
        if handle == 0 {
            return;
        }
        // SAFETY: reclaims the Box from nativeNew; the Java side zeroes its
        // handle first, so this runs at most once.
        drop(unsafe { Box::from_raw(handle as *mut PacketDnsInspector) });
    })
}
