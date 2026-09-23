#!/usr/bin/env node
/*
 * Publishes the GitHub release for a locally built Glove-Statistics APK.
 *
 * Why this is a Node script and not the .ps1 that ships next to it:
 * on this machine PowerShell's Invoke-RestMethod / curl.exe cannot complete any
 * HTTPS request ("基础连接已经关闭: 接收时发生错误", and curl reports
 * "schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS").
 * Both use Windows Schannel; `git` uses OpenSSL and works, and so does Node.
 * The API calls therefore go through Node's OpenSSL-backed fetch.
 *
 * Usage (token is read from the environment, never from a command line):
 *   $env:GH_TOKEN = '<token from git-credential-manager>'
 *   node .tooling/release/publish-v0.4.0.mjs
 *
 * The script is idempotent: an existing release for the tag is reused, and an
 * existing asset with the same name is replaced.
 */
import { readFileSync, statSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const REPO = 'Mr5U/Glove-statistics'
const TAG = 'v0.4.0'
const TARGET = 'main'
const ASSET_NAME = 'Glove-Statistics-0.4.0-release.apk'
// Built from code points so this file stays ASCII-clean: "缝手套记工 v0.4.0 — 厂房计件 + 同日合并（手套 + 厂房双账本）"
const TITLE = String.fromCodePoint(
  0x7F1D, 0x624B, 0x5957, 0x8BB0, 0x5DE5, 0x20, 0x76, 0x30, 0x2E, 0x34, 0x2E, 0x30, 0x20, 0x2014, 0x20,
  0x5382, 0x623F, 0x8BA1, 0x4EF6, 0x20, 0x2B, 0x20, 0x540C, 0x65E5, 0x5408, 0x5E76, 0x20,
  0xFF08, 0x624B, 0x5957, 0x20, 0x2B, 0x20, 0x5382, 0x623F, 0x53CC, 0x8D26, 0x672C, 0xFF09,
)

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..', '..')
const assetPath = join(root, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk')
const notesPath = join(here, 'RELEASE_NOTES_v0.4.0.md')

const token = process.env.GH_TOKEN
if (!token) {
  console.error('GH_TOKEN is not set. See the usage note at the top of this file.')
  process.exit(2)
}

const headers = {
  authorization: `token ${token}`,
  accept: 'application/vnd.github+json',
  'user-agent': 'Glove-Statistics-release',
  'x-github-api-version': '2022-11-28',
}

async function api(method, url, { body, raw } = {}) {
  const init = { method, headers: { ...headers } }
  if (body !== undefined) {
    init.headers['content-type'] = 'application/json; charset=utf-8'
    init.body = JSON.stringify(body)
  }
  if (raw !== undefined) {
    init.headers['content-type'] = 'application/vnd.android.package-archive'
    init.body = raw
  }
  const res = await fetch(url, init)
  const text = await res.text()
  if (!res.ok) {
    throw new Error(`${method} ${url} -> HTTP ${res.status} ${text.slice(0, 500)}`)
  }
  return text ? JSON.parse(text) : null
}

const notes = readFileSync(notesPath, 'utf8').replace(/\r\n/g, '\n')
const apkSize = statSync(assetPath).size

// 1. find or create the release for the tag
let release
try {
  release = await api('GET', `https://api.github.com/repos/${REPO}/releases/tags/${TAG}`)
  console.log(`found release id=${release.id}`)
} catch (error) {
  if (!String(error.message).includes('HTTP 404')) throw error
  release = await api('POST', `https://api.github.com/repos/${REPO}/releases`, {
    body: { tag_name: TAG, target_commitish: TARGET, draft: true, prerelease: false },
  })
  console.log(`created release id=${release.id}`)
}

// 2. publish it with the notes
const published = await api('PATCH', `https://api.github.com/repos/${REPO}/releases/${release.id}`, {
  body: { name: TITLE, body: notes, draft: false, prerelease: false },
})
console.log(`published: ${published.html_url}`)

// 3. replace the asset if it is already there, then upload
const existing = await api('GET', `https://api.github.com/repos/${REPO}/releases/${published.id}/assets`)
for (const asset of existing ?? []) {
  if (asset.name === ASSET_NAME) {
    console.log(`replacing existing asset id=${asset.id}`)
    await api('DELETE', `https://api.github.com/repos/${REPO}/releases/assets/${asset.id}`)
  }
}

const uploaded = await api(
  'POST',
  `https://uploads.github.com/repos/${REPO}/releases/${published.id}/assets?name=${encodeURIComponent(ASSET_NAME)}`,
  { raw: readFileSync(assetPath) },
)

console.log(`asset: ${uploaded.name} (${uploaded.size} bytes, local ${apkSize} bytes)`)
console.log(`download: ${uploaded.browser_download_url}`)
console.log(`RELEASE_URL=${published.html_url}`)
