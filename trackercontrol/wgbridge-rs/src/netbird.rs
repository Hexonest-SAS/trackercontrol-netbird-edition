//! NetBird Edition: split-tunnel prefixes for a tunnel run by another engine
//! (NetBird) instead of gotatun. Kept out of `policy.rs` so upstream updates
//! of that file merge without conflict.

use ipnetwork::IpNetwork;

use crate::policy::set_allowed_ips;

/// Parses a `;`-separated list of prefixes, the route format NetBird's
/// `TunAdapter.ConfigureInterface` uses. Blank entries are skipped; any
/// unparseable entry rejects the whole list.
pub fn parse_prefix_list(list: &str) -> Result<Vec<IpNetwork>, String> {
    list.split(';')
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .map(|s| {
            s.parse::<IpNetwork>()
                .map_err(|e| format!("invalid prefix {s:?}: {e}"))
        })
        .collect()
}

/// Loads the split prefixes of a tunnel not run by gotatun (the NetBird
/// engine). An invalid list clears them, which is the fail-closed full tunnel.
pub fn set_allowed_ips_from_list(list: &str) -> Result<usize, String> {
    match parse_prefix_list(list) {
        Ok(prefixes) => {
            set_allowed_ips(&prefixes);
            Ok(prefixes.len())
        }
        Err(e) => {
            set_allowed_ips(&[]);
            Err(e)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::policy::{dest_tunnelled, split_prefixes};

    fn nets(cidrs: &[&str]) -> Vec<IpNetwork> {
        cidrs.iter().map(|c| c.parse().unwrap()).collect()
    }

    fn tunnelled(prefixes: &[IpNetwork], dest: &str) -> bool {
        dest_tunnelled(prefixes, dest.parse().unwrap())
    }

    #[test]
    fn netbird_route_list_parses_into_split_prefixes() {
        let parsed = parse_prefix_list("100.102.0.0/16; 10.20.0.0/24;;fd91:d072::/32;").unwrap();
        assert_eq!(parsed, nets(&["100.102.0.0/16", "10.20.0.0/24", "fd91:d072::/32"]));
        let split = split_prefixes(&parsed);
        assert!(tunnelled(&split, "100.102.79.78"), "a NetBird peer is tunnelled");
        assert!(!tunnelled(&split, "1.1.1.1"), "Internet traffic leaves directly");
    }

    #[test]
    fn empty_or_invalid_route_list() {
        assert_eq!(parse_prefix_list("").unwrap(), Vec::<IpNetwork>::new());
        assert_eq!(parse_prefix_list(" ; ").unwrap(), Vec::<IpNetwork>::new());
        assert!(parse_prefix_list("100.102.0.0/16;not-a-prefix").is_err());
        assert!(parse_prefix_list("100.102.0.0/33").is_err());
    }
}
