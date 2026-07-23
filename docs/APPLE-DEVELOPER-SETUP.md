# Apple Developer Setup Checklist

Complete these steps to enable automated iOS app distribution via TestFlight.

---

## Prerequisites

- [ ] Apple Developer Program enrollment ($99/year) — [enroll here](https://developer.apple.com/programs/enroll/)
- [ ] Apple ID with Developer Program access
- [ ] Mac with Xcode 15+

---

## Step 1: Create App ID in Apple Developer Portal

**Time: 5 minutes**

Go to: https://developer.apple.com/account/resources/identifiers/list

- [ ] Click "+" to register new App ID
- [ ] Platform: iOS
- [ ] Description: `LibraVault`
- [ ] Bundle ID (Explicit): `xyz.libravault.ios`
- [ ] Capabilities: Leave all unchecked (privacy-first)
- [ ] Click "Continue" → "Register"

**Save:** Bundle ID = `xyz.libravault.ios`

---

## Step 2: Create App Record in App Store Connect

**Time: 5 minutes**

Go to: https://appstoreconnect.apple.com

- [ ] Click "My Apps" → "+"
- [ ] Select "New App"
- [ ] Fill in:
  - [ ] Platforms: iOS
  - [ ] Name: `LibraVault`
  - [ ] Primary Language: English
  - [ ] Bundle ID: `xyz.libravault.ios` (select from list)
  - [ ] SKU: `libravault-ios` (must be unique, not user-facing)
- [ ] Click "Create"

---

## Step 3: Create iOS Distribution Certificate

**Time: 10 minutes**

**On your Mac:**

- [ ] Open Keychain Access: `/Applications/Utilities/Keychain Access.app`
- [ ] Menu: Keychain Access → Certificate Assistant → Request a Certificate from a Certificate Authority
- [ ] Fill in:
  - [ ] Email Address: `robster@robster.org`
  - [ ] Common Name: `Rob`
  - [ ] Request: `Saved to disk`
  - [ ] Save as: `CertificateSigningRequest.certSigningRequest`

**In Apple Developer Portal:**

Go to: https://developer.apple.com/account/resources/certificates/list

- [ ] Click "+" to create new certificate
- [ ] Select: **iOS Distribution** (not Development)
- [ ] Upload `CertificateSigningRequest.certSigningRequest`
- [ ] Download the `.cer` file
- [ ] Double-click `.cer` to install in Keychain

**Export for GitHub:**

- [ ] Open Keychain Access
- [ ] Find certificate: "Apple Distribution: Rob"
- [ ] Right-click → Export
  - [ ] Format: `Personal Information Exchange (.p12)`
  - [ ] Save as: `AppleDistribution.p12`
  - [ ] Password: (leave blank or create one — you'll need this in GitHub next)

**Save:** `AppleDistribution.p12` file

---

## Step 4: Create App Store Connect API Key

**Time: 5 minutes**

Go to: https://appstoreconnect.apple.com/access/api

- [ ] Click "Keys" tab → "+"
- [ ] Name: `GitHub Actions CI/CD`
- [ ] Access Level: `App Manager`
- [ ] Click "Generate"
- [ ] Download the `.p8` file (one-time download!)
- [ ] Note the **Key ID** (shown in the list, e.g., `ABC123XYZ`)
- [ ] Note the **Issuer ID** (shown at the top, e.g., `12345678-1234-1234-1234-123456789012`)

**Save:** 
- `.p8` file
- Key ID
- Issuer ID

---

## Step 5: Get Your Team ID

**Time: 2 minutes**

Go to: https://developer.apple.com/account/#/membership

- [ ] Find "Team ID" (e.g., `H74LNL8UCG`)

**Save:** Team ID

---

## Step 6: Configure GitHub Secrets

**Time: 5 minutes**

Run these commands in your terminal (replace values with your actual data):

```bash
cd /home/rob/git/LibraVault/reader

# 1. Bundle ID
gh secret set APPLE_BUNDLE_ID --body "xyz.libravault.ios"

# 2. Team ID
gh secret set APPLE_TEAM_ID --body "H74LNL8UCG"

# 3. Distribution Certificate (.p12) as base64
cat ~/Desktop/AppleDistribution.p12 | base64 | gh secret set APPLE_DISTRIBUTION_CERT_BASE64

# 4. API Key ID
gh secret set APP_STORE_CONNECT_KEY_ID --body "ABC123XYZ"

# 5. Issuer ID
gh secret set APP_STORE_CONNECT_ISSUER_ID --body "12345678-1234-1234-1234-123456789012"

# 6. Private Key (.p8 file)
cat ~/Downloads/AuthKey_*.p8 | gh secret set APP_STORE_CONNECT_PRIVATE_KEY
```

**Verify secrets were set:**
```bash
gh secret list --repo LibraVault/reader
```

All 6 secrets should appear in the list (values are hidden).

- [ ] APPLE_BUNDLE_ID
- [ ] APPLE_TEAM_ID
- [ ] APPLE_DISTRIBUTION_CERT_BASE64
- [ ] APP_STORE_CONNECT_KEY_ID
- [ ] APP_STORE_CONNECT_ISSUER_ID
- [ ] APP_STORE_CONNECT_PRIVATE_KEY

---

## Step 7: Set Up TestFlight Internal Testers (Optional)

**Time: 5 minutes**

Go to: https://appstoreconnect.apple.com/testflight/ios/testers

- [ ] Click "+" to add internal testers
- [ ] Add your Apple ID email
- [ ] Add any beta tester emails
- [ ] Click "Save"

(You'll send build invites later after the first TestFlight upload)

---

## Step 8: Trigger First Automated Build

**Time: 45 minutes**

```bash
# 1. Push a commit to feat/v3-ios-port
git push origin feat/v3-ios-port

# 2. Go to GitHub Actions
# https://github.com/LibraVault/reader/actions/workflows/ios-testflight.yml

# 3. Click "Run workflow" button
# 4. Leave branch as "feat/v3-ios-port"
# 5. Click "Run workflow"

# Watch the build progress in the Actions tab
```

**Expected output:**
- ✅ KMP frameworks built (30 min)
- ✅ iOS app built & archived (10 min)
- ✅ Uploaded to TestFlight (5 min)

---

## Step 9: Invite Testers on TestFlight

**Time: 2 minutes**

Once the build appears in TestFlight (usually 15 min after upload):

Go to: https://appstoreconnect.apple.com/testflight/ios/builds

- [ ] Select the latest build
- [ ] Click "Testers"
- [ ] Add testers from your internal testing group
- [ ] Testers will receive email invites

---

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Certificate not found in Keychain | Re-run Step 3, ensure `.cer` is double-clicked to import |
| GitHub secret upload fails | Use full `.p8` file path; test with `cat ~/path/to/file.p8` first |
| TestFlight build rejected | Check App Store Connect privacy nutrition label is filled (Step 10) |
| Build succeeds but no TestFlight upload | Verify all 6 secrets are set with `gh secret list` |

---

## Summary

✅ All steps complete when:
- [ ] 6 GitHub secrets configured and verified
- [ ] First build triggered and uploaded to TestFlight
- [ ] Testers can download from TestFlight

**Total setup time: ~1.5 hours**

**From then on:**
- Manually trigger TestFlight builds via: `GitHub Actions → Run workflow`
- Builds complete in ~45 minutes
- Testers notified automatically

---

## Next: Distribute to Testers

See [iOS-AUTOMATED-BUILDS.md](iOS-AUTOMATED-BUILDS.md) for:
- How to trigger builds
- How to invite testers
- How to collect feedback
