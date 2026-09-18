#!/usr/bin/env bash
set -euo pipefail

IMAGE_REPOSITORY="${IMAGE_REPOSITORY:-ghcr.io/epsuchti/fintube-server}"
PLATFORMS="${PLATFORMS:-linux/amd64,linux/arm64}"
LATEST_TAG="${LATEST_TAG:-latest}"
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_FILE="${VERSION_FILE:-${ROOT_DIR}/deploy/version.txt}"

if [[ $# -gt 1 ]]; then
  echo "Usage: $0 [image-tag]"
  exit 1
fi

trim() {
  local value="$1"
  value="${value%$'\r'}"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  printf '%s\n' "${value}"
}

maybe_bump_version() {
  local current_tag="$1"
  local next_tag
  local answer

  if [[ ! -t 0 ]]; then
    printf '%s\n' "${current_tag}"
    return 0
  fi

  if [[ "${current_tag}" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]]; then
    next_tag="${BASH_REMATCH[1]}.${BASH_REMATCH[2]}.$((BASH_REMATCH[3] + 1))"
  elif [[ "${current_tag}" =~ ^[0-9]+$ ]]; then
    next_tag="$((current_tag + 1))"
  else
    echo "Current version '${current_tag}' is not numeric or SemVer; skipping bump prompt."
    printf '%s\n' "${current_tag}"
    return 0
  fi

  read -r -p "Bump version from ${current_tag} to ${next_tag}? [y/N] " answer
  if [[ "${answer}" =~ ^[Yy]$ ]]; then
    printf '%s\n' "${next_tag}" > "${VERSION_FILE}"
    echo "Updated ${VERSION_FILE} to ${next_tag}" >&2
    printf '%s\n' "${next_tag}"
    return 0
  fi

  printf '%s\n' "${current_tag}"
}

if [[ $# -eq 1 ]]; then
  TAG="$(trim "$1")"
else
  if [[ ! -f "${VERSION_FILE}" ]]; then
    echo "Missing version file: ${VERSION_FILE}"
    exit 1
  fi

  TAG="$(trim "$(cat "${VERSION_FILE}")")"
  TAG="$(maybe_bump_version "${TAG}")"
fi

if [[ -z "${TAG}" ]]; then echo "Image tag cannot be empty"; exit 1; fi
if [[ "${IMAGE_REPOSITORY}" == *YOUR_GITHUB_USERNAME* ]]; then
  echo "Set IMAGE_REPOSITORY to your container registry path before releasing."
  exit 1
fi

echo "Building FinTube ${TAG}"
(cd "${ROOT_DIR}" && ./mvnw clean package)

BUILDER_NAME="${BUILDER_NAME:-fintube-multiarch}"
docker buildx inspect "${BUILDER_NAME}" >/dev/null 2>&1 || docker buildx create --name "${BUILDER_NAME}" --use
docker buildx use "${BUILDER_NAME}" >/dev/null
docker buildx build --platform "${PLATFORMS}" -t "${IMAGE_REPOSITORY}:${TAG}" -t "${IMAGE_REPOSITORY}:${LATEST_TAG}" --push "${ROOT_DIR}"

git -C "${ROOT_DIR}" rev-parse --is-inside-work-tree >/dev/null
git -C "${ROOT_DIR}" tag -f -a "${TAG}" -m "Release ${TAG}"
echo "Published ${IMAGE_REPOSITORY}:${TAG} and ${IMAGE_REPOSITORY}:${LATEST_TAG}"
echo "Push the tag when ready: git -C ${ROOT_DIR} push --force origin refs/tags/${TAG}"
