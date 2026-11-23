"""Mechanical audit of the exported component surface of a decoded AndroidManifest.xml.

Two separate questions, because they are not the same one:

1. **Reachability.** Every component that has to be reachable from outside the app must be
   exported. A launch activity, a boot receiver and an accessibility binding all fail silently
   when the flag is missing — the feature simply never fires.
2. **Exposure.** An exported component with no `android:permission` can be driven by any app on
   the device. That is correct for the launch activity and the boot receiver, and it is why the
   accessibility service carries a BIND permission: the platform refuses to bind without it.

Anything exported that is not on the expected list is reported, and so is anything required that
is no longer exported. Exit code 1 means the surface moved.

Usage: python audit_surface.py <decoded AndroidManifest.xml>
"""

import sys
import xml.etree.ElementTree as ET

ANDROID = "{http://schemas.android.com/apk/res/android}"

# Deliberately reachable. Removing the export breaks the payload, not the security posture.
EXPECTED_EXPORTED = {
    ("activity", ".MainActivity"),
    ("receiver", ".BootReceiver"),
    ("service", ".KeyloggerService"),
}

# Deliberately reachable *and* unguarded. A guard added here is fine; the point is that the
# exported set does not quietly grow past these three.
EXPECTED_UNGUARDED = {
    ("activity", ".MainActivity"),
    ("receiver", ".BootReceiver"),
}


def main(path):
    app = ET.parse(path).getroot().find("application")
    if app is None:
        print("no <application>")
        return 1

    exported_set = set()
    unguarded_set = set()
    rows = []

    for kind in ("activity", "activity-alias", "service", "receiver", "provider"):
        for node in app.findall(kind):
            short = node.get(ANDROID + "name", "").replace("com.mobilesvc.agent", "").strip()
            exported = node.get(ANDROID + "exported") == "true"
            permission = node.get(ANDROID + "permission")
            actions = sorted({
                a.get(ANDROID + "name", "")
                for f in node.findall("intent-filter")
                for a in f.findall("action")
            })

            if exported:
                exported_set.add((kind, short))
                verdict = "GUARDED" if permission else "OPEN"
                if not permission:
                    unguarded_set.add((kind, short))
            else:
                verdict = "internal"

            rows.append((kind, short, "yes" if exported else "no", permission or "-", verdict,
                         ",".join(actions) or "-"))

    width = max(len(r[1]) for r in rows)
    print(f"{'TYPE':<10}{'COMPONENT':<{width + 2}}{'EXPORTED':<10}{'VERDICT':<9}PERMISSION")
    for kind, short, exported, permission, verdict, actions in rows:
        print(f"{kind:<10}{short:<{width + 2}}{exported:<10}{verdict:<9}{permission}")
        if actions != "-":
            pad = " " * (width + 12)
            print(f"{pad}actions: {actions}")

    missing = sorted(EXPECTED_EXPORTED - exported_set)
    extra = sorted(exported_set - EXPECTED_EXPORTED)
    new_open = sorted(unguarded_set - EXPECTED_UNGUARDED)

    print()
    for label, items in (("no longer exported", missing),
                         ("newly exported", extra),
                         ("newly unguarded", new_open)):
        if items:
            print(f"{label}:")
            for kind, short in items:
                print(f"  {kind} {short}")

    ok = not missing and not extra
    print()
    print("surface matches expectation" if ok else "surface differs from expectation")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
