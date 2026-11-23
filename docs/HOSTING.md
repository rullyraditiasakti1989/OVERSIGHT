# LOCATE — hosting setup

The LOCATE page is static and can be served from any static host. This guide uses Surge because the
original deployment did, but no account credentials belong in this repository.

| Field | Value |
|---|---|
| Provider | Surge (free static plan) or any static host |
| Domain | `<your-subdomain>.surge.sh` |
| Account | `<your-email>` |
| Publishing credential | stored locally, never committed |

## Publish

```powershell
$env:SURGE_TOKEN="<your-surge-token>"
surge locate <your-subdomain>.surge.sh
```

Keep the token in your shell environment or a password manager. `docs/LOCATE.md` shows which values
in `locate/index.html` must be changed before publishing: the ntfy topic and the redirect target.

## Notes

- `locate/index.html` ships with a placeholder endpoint. The page will not deliver fixes until you
  replace `https://ntfy.sh/<YOUR_TOPIC>` with your own ntfy topic.
- A ntfy topic is public. Anyone who knows it can subscribe, so use a long random topic and rotate
  it when the old one has been shared.
- The URL is visible to the browser that opens it. There is no disguise and no tracking hiding.
- If your host requires email verification, complete it in your own account before publishing;
  this repository cannot carry a verification mailbox.
