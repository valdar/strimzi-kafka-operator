/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.Secret;
import io.strimzi.certs.CertManager;
import io.strimzi.certs.OpenSslCertManager;
import io.strimzi.certs.SecretCertProvider;
import io.strimzi.operator.cluster.InvalidConfigMapException;
import io.strimzi.operator.cluster.Reconciliation;
import io.strimzi.operator.cluster.model.AssemblyType;
import io.strimzi.operator.cluster.model.Labels;
import io.strimzi.operator.cluster.operator.resource.ConfigMapOperator;
import io.strimzi.operator.cluster.operator.resource.SecretOperator;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.shareddata.Lock;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Collectors;

/**
 * <p>Abstract assembly creation, update, read, deletion, etc.</p>
 *
 * <p>An assembly is a collection of Kubernetes resources of various types
 * (e.g. Services, StatefulSets, Deployments etc) which operate together to provide some functionality.</p>
 *
 * <p>This class manages a per-assembly locking strategy so only one operation per assembly
 * can proceed at once.</p>
 */
public abstract class AbstractAssemblyOperator {

    private static final Logger log = LogManager.getLogger(AbstractAssemblyOperator.class.getName());

    protected static final int LOCK_TIMEOUT = 60000;

    protected final Vertx vertx;
    protected final boolean isOpenShift;
    protected final AssemblyType assemblyType;
    protected final ConfigMapOperator configMapOperations;
    protected final SecretOperator secretOperations;

    /**
     * @param vertx The Vertx instance
     * @param isOpenShift True iff running on OpenShift
     * @param assemblyType Assembly type
     * @param configMapOperations For operating on ConfigMaps
     * @param secretOperations For operating on Secrets
     */
    protected AbstractAssemblyOperator(Vertx vertx, boolean isOpenShift, AssemblyType assemblyType,
                                       ConfigMapOperator configMapOperations, SecretOperator secretOperations) {
        this.vertx = vertx;
        this.isOpenShift = isOpenShift;
        this.assemblyType = assemblyType;
        this.configMapOperations = configMapOperations;
        this.secretOperations = secretOperations;
    }

    /**
     * Gets the name of the lock to be used for operating on the given {@code assemblyType}, {@code namespace} and
     * cluster {@code name}
     * @param assemblyType The type of cluster
     * @param namespace The namespace containing the cluster
     * @param name The name of the cluster
     */
    protected final String getLockName(AssemblyType assemblyType, String namespace, String name) {
        return "lock::" + namespace + "::" + assemblyType + "::" + name;
    }

    /**
     * Subclasses implement this method to create or update the cluster. The implementation
     * should not assume that any resources are in any particular state (e.g. that the absence on
     * one resource means that all resources need to be created).
     * @param reconciliation Unique identification for the reconciliation
     * @param assemblyCm ConfigMap with cluster configuration.
     * @param assemblySecrets Secrets related to the cluster
     * @param handler Completion handler
     */
    protected abstract void createOrUpdate(Reconciliation reconciliation, ConfigMap assemblyCm, List<Secret> assemblySecrets, Handler<AsyncResult<Void>> handler);

    /**
     * Subclasses implement this method to delete the cluster.
     * @param handler Completion handler
     */
    protected abstract void delete(Reconciliation reconciliation, Handler<AsyncResult<Void>> handler);

    /**
     * The name of the given {@code resource}, as read from its metadata.
     * @param resource The resource
     */
    protected static String name(HasMetadata resource) {
        if (resource != null) {
            ObjectMeta metadata = resource.getMetadata();
            if (metadata != null) {
                return metadata.getName();
            }
        }
        return null;
    }

    private final void reconcileCertificate(String namespace, Handler<AsyncResult<Void>> handler) {

        vertx.createSharedWorkerExecutor("kubernetes-ops-pool").executeBlocking(
                future -> {

                    if (secretOperations.get(namespace, "internal-ca") == null) {
                        log.info("Generating internal CA certificate ...");
                        File internalCAkeyFile = null;
                        File internalCAcertFile = null;
                        try {
                            CertManager certManager = new OpenSslCertManager();
                            internalCAkeyFile = File.createTempFile("tls", "internal-ca-key");
                            internalCAcertFile = File.createTempFile("tls", "internal-ca-cert");
                            certManager.generateSelfSignedCert(internalCAkeyFile, internalCAcertFile, 365);

                            SecretCertProvider secretCertProvider = new SecretCertProvider();
                            Secret secret = secretCertProvider.createSecret(namespace, "internal-ca",
                                    "internal-ca.key", "internal-ca.crt",
                                    internalCAkeyFile, internalCAcertFile, Collections.emptyMap());

                            secretOperations.reconcile(namespace, "internal-ca", secret)
                                    .compose(future::complete, future);

                        } catch (IOException e) {
                            future.fail(e);
                        } finally {
                            if (internalCAkeyFile != null)
                                internalCAkeyFile.delete();
                            if (internalCAcertFile != null)
                                internalCAcertFile.delete();
                        }
                        log.info("... end generating certificate");
                    } else {
                        log.info("The internal CA certificate already exists");
                        future.complete();
                    }
                }, true,
                res -> {
                    if (res.succeeded())
                        handler.handle(Future.succeededFuture());
                    else
                        handler.handle(Future.failedFuture(res.cause()));
                }
        );
    }

    /**
     * Reconcile assembly resources in the given namespace having the given {@code assemblyName}.
     * Reconciliation works by getting the assembly ConfigMap in the given namespace with the given assemblyName and
     * comparing with the corresponding {@linkplain #getResources(String) resource}.
     * <ul>
     * <li>An assembly will be {@linkplain #createOrUpdate(Reconciliation, ConfigMap, List, Handler) created or updated} if ConfigMap is without same-named resources</li>
     * <li>An assembly will be {@linkplain #delete(Reconciliation, Handler) deleted} if resources without same-named ConfigMap</li>
     * </ul>
     */
    public final void reconcileAssembly(Reconciliation reconciliation, Handler<AsyncResult<Void>> handler) {
        String namespace = reconciliation.namespace();
        String assemblyName = reconciliation.assemblyName();
        final String lockName = getLockName(assemblyType, namespace, assemblyName);
        vertx.sharedData().getLockWithTimeout(lockName, LOCK_TIMEOUT, res -> {
            if (res.succeeded()) {
                log.debug("{}: Lock {} acquired", reconciliation, lockName);
                Lock lock = res.result();

                try {
                    // get ConfigMap and related resources for the specific cluster
                    ConfigMap cm = configMapOperations.get(namespace, assemblyName);

                    if (cm != null) {
                        log.info("{}: assembly {} should be created or updated", reconciliation, assemblyName);
                        reconcileCertificate(namespace, certResult -> {

                            Labels labels = Labels.forCluster(assemblyName);
                            List<Secret> secrets = secretOperations.list(namespace, labels);
                            secrets.add(secretOperations.get(namespace, "internal-ca"));
                            //Map<String, Secret> map = secrets.stream().collect(Collectors.toMap(s -> s.getMetadata().getName(), s -> s));

                            if (certResult.succeeded()) {
                                createOrUpdate(reconciliation, cm, secrets, createResult -> {
                                    lock.release();
                                    log.debug("{}: Lock {} released", reconciliation, lockName);
                                    if (createResult.failed()) {
                                        if (createResult.cause() instanceof InvalidConfigMapException) {
                                            log.error(createResult.cause().getMessage());
                                        } else {
                                            log.error(createResult.cause().toString());
                                        }
                                    } else {
                                        handler.handle(createResult);
                                    }
                                });
                            } else {
                                log.error(certResult.cause().toString());
                                lock.release();
                            }
                        });
                    } else {
                        log.info("{}: assembly {} should be deleted", reconciliation, assemblyName);
                        delete(reconciliation, deleteResult -> {
                            lock.release();
                            log.debug("{}: Lock {} released", reconciliation, lockName);
                            handler.handle(deleteResult);
                        });
                    }
                } catch (Throwable ex) {
                    lock.release();
                    log.debug("{}: Lock {} released", reconciliation, lockName);
                    handler.handle(Future.failedFuture(ex));
                }
            } else {
                log.warn("{}: Failed to acquire lock {}.", reconciliation, lockName);
            }
        });
    }

    /**
     * Reconcile assembly resources in the given namespace having the given selector.
     * Reconciliation works by getting the assembly ConfigMaps in the given namespace with the given selector and
     * comparing with the corresponding {@linkplain #getResources(String) resource}.
     * <ul>
     * <li>An assembly will be {@linkplain #createOrUpdate(Reconciliation, ConfigMap, List, Handler) created} for all ConfigMaps without same-named resources</li>
     * <li>An assembly will be {@linkplain #delete(Reconciliation, Handler) deleted} for all resources without same-named ConfigMaps</li>
     * </ul>
     *
     * @param trigger A description of the triggering event (timer or watch), used for logging
     * @param namespace The namespace
     * @param selector The selector
     */
    public final CountDownLatch reconcileAll(String trigger, String namespace, Labels selector) {
        Labels selectorWithCluster = selector.withType(assemblyType);

        // get ConfigMaps with kind=cluster&type=kafka (or connect, or connect-s2i) for the corresponding cluster type
        List<ConfigMap> cms = configMapOperations.list(namespace, selectorWithCluster);
        Set<String> cmsNames = cms.stream().map(cm -> cm.getMetadata().getName()).collect(Collectors.toSet());
        log.debug("reconcileAll({}, {}): ConfigMaps with labels {}: {}", assemblyType, trigger, selectorWithCluster, cmsNames);

        // get resources with kind=cluster&type=kafka (or connect, or connect-s2i)
        List<? extends HasMetadata> resources = getResources(namespace);
        // now extract the cluster name from those
        Set<String> resourceNames = resources.stream()
                .filter(r -> Labels.kind(r) == null) // exclude Cluster CM, which won't have a cluster label
                .map(Labels::cluster)
                .collect(Collectors.toSet());
        log.debug("reconcileAll({}, {}): Other resources with labels {}: {}", assemblyType, trigger, selectorWithCluster, resourceNames);

        cmsNames.addAll(resourceNames);

        // We use a latch so that callers (specifically, test callers) know when the reconciliation is complete
        // Using futures would be more complex for no benefit
        CountDownLatch latch = new CountDownLatch(cmsNames.size());

        for (String name: cmsNames) {
            Reconciliation reconciliation = new Reconciliation(trigger, assemblyType, namespace, name);
            reconcileAssembly(reconciliation, result -> {
                if (result.succeeded()) {
                    log.info("{}: Assembly reconciled", reconciliation);
                } else {
                    Throwable cause = result.cause();
                    if (cause instanceof InvalidConfigMapException) {
                        log.warn("{}: Failed to reconcile {}", reconciliation, cause.getMessage());
                    } else {
                        log.warn("{}: Failed to reconcile {}", reconciliation, cause);
                    }
                }
                latch.countDown();
            });
        }

        return latch;
    }

    /**
     * Gets all the assembly resources (for all assemblies) in the given namespace.
     * Assembly CMs may be included in the result.
     * @param namespace The namespace
     * @return The matching resources.
     */
    protected abstract List<HasMetadata> getResources(String namespace);

}
