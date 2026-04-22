# schema-registry-block-delete

Custom Schema Registry extension that blocks HTTP `DELETE` at the application
layer via Confluent's `SchemaRegistryResourceExtension` SPI. Packaged as a
custom SR Docker image, published to GHCR, deployed to EKS.

## Architecture

```
┌──────────────────┐   git push      ┌──────────────────┐
│ dev's laptop     │ ───────────►    │ GitHub           │
└──────────────────┘                 └────────┬─────────┘
                                              │ triggers
                                              ▼
                                     ┌──────────────────┐
                                     │ GitHub Actions   │
                                     │ ─ mvn verify     │
                                     │ ─ docker build   │
                                     │ ─ push to ghcr   │
                                     └────────┬─────────┘
                                              │ docker push
                                              ▼
                                     ┌──────────────────┐
                                     │ ghcr.io/OWNER/   │
                                     │   REPO:1.0.0     │
                                     └────────┬─────────┘
                                              │ docker pull
                                              ▼
                                     ┌──────────────────┐
                                     │ EKS cluster      │
                                     │  SR pod (x2)     │
                                     └──────────────────┘
```

The JAR is **baked into the image** — not bind-mounted at runtime. This is the
production pattern: immutable images, versioned releases, nothing external to
pull from the pod's filesystem.

## Repository layout

```
.
├── src/main/java/…/BlockDeleteExtension.java   # the extension
├── src/test/java/…/BlockDeleteExtensionTest.java
├── pom.xml                    # Maven build + test config
├── Dockerfile                 # multi-stage: builder + SR runtime
├── docker-compose.yml         # PROD-style: uses ghcr image
├── docker-compose.dev.yml     # DEV: bind-mount locally-built JAR
├── k8s/                       # EKS manifests (adapt for your cluster)
│   ├── 01-namespace.yaml
│   ├── 02-image-pull-secret.yaml
│   └── 03-schema-registry.yaml
├── .github/workflows/
│   ├── pr.yml       # compile + test on every PR
│   ├── build.yml    # push image on every merge to main
│   └── release.yml  # versioned image + GitHub Release on git tag
└── docs/
    └── EKS-DEPLOYMENT.md
```

## Quick start — local dev with your own Java changes

```bash
# Edit Java → rebuild JAR → restart SR
mvn package
docker compose -f docker-compose.dev.yml up -d --force-recreate
docker compose -f docker-compose.dev.yml logs schema-registry | grep BlockDelete
curl -i -X DELETE http://localhost:8081/subjects/test   # → 405
```

## Quick start — run the published image

```bash
# Edit docker-compose.yml, replace OWNER/REPO with your GitHub path
docker compose pull
docker compose up -d
curl -i -X DELETE http://localhost:8081/subjects/test   # → 405
```

## CI/CD flow

| Event | Workflow | What happens |
|---|---|---|
| Pull request to `main` | `pr.yml` | `mvn verify` + `docker build`. Blocks merge if red. |
| Push to `main` | `build.yml` | Tests + pushes `ghcr.io/OWNER/REPO:latest` and `:main-<sha>`. |
| Push tag `v1.2.3` | `release.yml` | Tests + pushes `:1.2.3`, `:1.2`, `:1`, `:latest`. Creates GitHub Release with JAR attached. |

### Cutting a release

```bash
git tag v1.0.0
git push origin v1.0.0
# Wait 2-3 min for Actions to finish
# Then go to Releases tab → JAR attached, image pushed
```

## Deploying to EKS

1. **Make the ghcr image pullable from your cluster.** Create a
   `docker-registry` secret with a GitHub PAT scoped to `read:packages`:

   ```bash
   kubectl -n schema-registry create secret docker-registry ghcr-pull-secret \
     --docker-server=ghcr.io \
     --docker-username=<github-user> \
     --docker-password=<PAT> \
     --docker-email=<email>
   ```

2. **Update `k8s/03-schema-registry.yaml`:**
   - Replace `OWNER/REPO` with your GitHub path.
   - Set the Kafka bootstrap env var to your MSK broker endpoints.
   - Adjust `replicas`, `resources`, and probes for your load.

3. **Apply:**
   ```bash
   kubectl apply -f k8s/
   kubectl -n schema-registry rollout status deploy/schema-registry
   ```

4. **Verify:**
   ```bash
   kubectl -n schema-registry port-forward svc/schema-registry 8081:8081 &
   curl -i -X DELETE http://localhost:8081/subjects/test   # → 405
   ```

5. **See the extension loaded** in pod logs:
   ```bash
   kubectl -n schema-registry logs deploy/schema-registry | grep BlockDelete
   # → BlockDeleteExtension REGISTERED
   ```

For GitOps: point ArgoCD at your `k8s/` directory. Bump the image tag via PR
to roll out new versions.

## Testing

```bash
mvn test
```

The test suite covers:
- DELETE is blocked with 405 + proof header
- Method case-insensitivity (`delete`, `DELETE`)
- GET/POST/PUT/PATCH/HEAD/OPTIONS pass through
- Filter doesn't NPE when URI info is null

## Production hardening checklist (not done here)

- [ ] Add cosign signing of the image in `release.yml`
- [ ] Generate SBOM (syft) + scan (grype/trivy) on every build
- [ ] Dependabot for Maven + Docker + Actions
- [ ] Branch protection: require PR check + 1 reviewer on `main`
- [ ] `CODEOWNERS` file to route reviews
- [ ] Pin Confluent base image by digest, not `:7.6.1` tag
- [ ] Private ECR mirror inside CBA VPC (instead of pulling from ghcr in prod)
- [ ] NetworkPolicy restricting which pods can reach SR

## License

MIT.
