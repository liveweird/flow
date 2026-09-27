#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 <local-image-tag>" >&2
    exit 2
fi

image_tag=$1
case "$image_tag" in
    "" | [.-]* | *[!A-Za-z0-9_.-]*)
        echo "invalid Docker image tag: $image_tag" >&2
        exit 2
        ;;
esac
if [ "${#image_tag}" -gt 128 ]; then
    echo "Docker image tag is longer than 128 characters" >&2
    exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
rendered_dir=$(mktemp -d)
trap 'rm -rf "$rendered_dir"' EXIT HUP INT TERM

# The Secret is intentionally excluded: create it out of band using secret.yaml's header.
# Both Deployments carry the same `image: flow-app:local-build` render placeholder, so both get
# the tag substitution below.
for manifest in "$script_dir"/*.yaml; do
    case "$(basename -- "$manifest")" in
        secret.yaml | web-deployment.yaml | worker-deployment.yaml) continue ;;
    esac
    cp "$manifest" "$rendered_dir/"
done

for deployment in web-deployment.yaml worker-deployment.yaml; do
    sed "s|image: flow-app:local-build|image: flow-app:$image_tag|" \
        "$script_dir/$deployment" >"$rendered_dir/$deployment"
done

kubectl -n flow get secret flow-secrets >/dev/null
kubectl apply -f "$rendered_dir"
