/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.operator.assembly;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.ImageStream;
import io.strimzi.operator.cluster.Reconciliation;
import io.strimzi.operator.cluster.ResourceUtils;
import io.strimzi.operator.cluster.model.AssemblyType;
import io.strimzi.operator.cluster.operator.resource.BuildConfigOperator;
import io.strimzi.operator.cluster.operator.resource.ConfigMapOperator;
import io.strimzi.operator.cluster.operator.resource.DeploymentConfigOperator;
import io.strimzi.operator.cluster.operator.resource.ImageStreamOperator;
import io.strimzi.operator.cluster.operator.resource.SecretOperator;
import io.strimzi.operator.cluster.operator.resource.ServiceOperator;
import io.strimzi.operator.cluster.model.KafkaConnectCluster;
import io.strimzi.operator.cluster.model.KafkaConnectS2ICluster;
import io.strimzi.operator.cluster.model.Labels;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.Arrays.asList;
import static java.util.Collections.singleton;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(VertxUnitRunner.class)
public class KafkaConnectS2IAssemblyOperatorTest {

    protected static Vertx vertx;

    @BeforeClass
    public static void before() {
        vertx = Vertx.vertx();
    }

    @AfterClass
    public static void after() {
        vertx.close();
    }

    @Test
    public void testCreateCluster(TestContext context) {
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        DeploymentConfigOperator mockDcOps = mock(DeploymentConfigOperator.class);
        BuildConfigOperator mockBcOps = mock(BuildConfigOperator.class);
        ImageStreamOperator mockIsOps = mock(ImageStreamOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        ConfigMap clusterCm = ResourceUtils.createEmptyKafkaConnectS2IClusterConfigMap(clusterCmNamespace, clusterCmName);
        when(mockCmOps.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);

        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(anyString(), anyString(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<DeploymentConfig> dcCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
        when(mockDcOps.reconcile(anyString(), anyString(), dcCaptor.capture())).thenReturn(Future.succeededFuture());
        when(mockDcOps.scaleDown(anyString(), anyString(), anyInt())).thenReturn(Future.succeededFuture(42));
        when(mockDcOps.scaleUp(anyString(), anyString(), anyInt())).thenReturn(Future.succeededFuture(42));

        ArgumentCaptor<ImageStream> isCaptor = ArgumentCaptor.forClass(ImageStream.class);
        when(mockIsOps.reconcile(anyString(), anyString(), isCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<BuildConfig> bcCaptor = ArgumentCaptor.forClass(BuildConfig.class);
        when(mockBcOps.reconcile(anyString(), anyString(), bcCaptor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectS2IAssemblyOperator ops = new KafkaConnectS2IAssemblyOperator(vertx, true,
                mockCmOps, mockDcOps, mockServiceOps, mockIsOps, mockBcOps, mockSecretOps);

        KafkaConnectS2ICluster connect = KafkaConnectS2ICluster.fromConfigMap(clusterCm);

        Async async = context.async();
        ops.createOrUpdate(new Reconciliation("test-trigger", AssemblyType.CONNECT_S2I, clusterCmNamespace, clusterCmName), clusterCm, Collections.EMPTY_LIST, createResult -> {
            context.assertTrue(createResult.succeeded());

            // Vertify service
            List<Service> capturedServices = serviceCaptor.getAllValues();
            context.assertEquals(1, capturedServices.size());
            Service service = capturedServices.get(0);
            context.assertEquals(connect.getName(), service.getMetadata().getName());
            context.assertEquals(connect.generateService(), service, "Services are not equal");

            // Verify Deployment Config
            List<DeploymentConfig> capturedDc = dcCaptor.getAllValues();
            context.assertEquals(1, capturedDc.size());
            DeploymentConfig dc = capturedDc.get(0);
            context.assertEquals(connect.getName(), dc.getMetadata().getName());
            context.assertEquals(connect.generateDeploymentConfig(), dc, "Deployment Configs are not equal");

            // Verify Build Config
            List<BuildConfig> capturedBc = bcCaptor.getAllValues();
            context.assertEquals(1, capturedBc.size());
            BuildConfig bc = capturedBc.get(0);
            context.assertEquals(connect.getName(), dc.getMetadata().getName());
            context.assertEquals(connect.generateBuildConfig(), bc, "Build Configs are not equal");

            // Verify Image Streams
            List<ImageStream> capturedIs = isCaptor.getAllValues();
            context.assertEquals(2, capturedIs.size());
            int sisIndex = (connect.getSourceImageStreamName()).equals(capturedIs.get(0).getMetadata().getName()) ? 0 : 1;
            int tisIndex = (connect.getName()).equals(capturedIs.get(0).getMetadata().getName()) ? 0 : 1;

            ImageStream sis = capturedIs.get(sisIndex);
            context.assertEquals(connect.getSourceImageStreamName(), sis.getMetadata().getName());
            context.assertEquals(connect.generateSourceImageStream(), sis, "Source Image Streams are not equal");

            ImageStream tis = capturedIs.get(tisIndex);
            context.assertEquals(connect.getName(), tis.getMetadata().getName());
            context.assertEquals(connect.generateTargetImageStream(), tis, "Target Image Streams are not equal");

            async.complete();
        });
    }

    @Test
    public void testUpdateClusterNoDiff(TestContext context) {
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        DeploymentConfigOperator mockDcOps = mock(DeploymentConfigOperator.class);
        BuildConfigOperator mockBcOps = mock(BuildConfigOperator.class);
        ImageStreamOperator mockIsOps = mock(ImageStreamOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        ConfigMap clusterCm = ResourceUtils.createEmptyKafkaConnectS2IClusterConfigMap(clusterCmNamespace, clusterCmName);
        KafkaConnectS2ICluster connect = KafkaConnectS2ICluster.fromConfigMap(clusterCm);
        when(mockCmOps.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockServiceOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateService());
        when(mockDcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateDeploymentConfig());
        when(mockIsOps.get(clusterCmNamespace, connect.getSourceImageStreamName())).thenReturn(connect.generateSourceImageStream());
        when(mockIsOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateTargetImageStream());
        when(mockBcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateBuildConfig());

        ArgumentCaptor<String> serviceNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(serviceNamespaceCaptor.capture(), serviceNameCaptor.capture(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DeploymentConfig> dcCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
        when(mockDcOps.reconcile(dcNamespaceCaptor.capture(), dcNameCaptor.capture(), dcCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleUpNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleUpNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleUpReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleUp(dcScaleUpNamespaceCaptor.capture(), dcScaleUpNameCaptor.capture(), dcScaleUpReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleDownNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleDownNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleDownReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleDown(dcScaleDownNamespaceCaptor.capture(), dcScaleDownNameCaptor.capture(), dcScaleDownReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> isNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> isNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ImageStream> isCaptor = ArgumentCaptor.forClass(ImageStream.class);
        when(mockIsOps.reconcile(isNamespaceCaptor.capture(), isNameCaptor.capture(), isCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> bcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BuildConfig> bcCaptor = ArgumentCaptor.forClass(BuildConfig.class);
        when(mockBcOps.reconcile(bcNamespaceCaptor.capture(), bcNameCaptor.capture(), bcCaptor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectS2IAssemblyOperator ops = new KafkaConnectS2IAssemblyOperator(vertx, true,
                mockCmOps, mockDcOps, mockServiceOps, mockIsOps, mockBcOps, mockSecretOps);

        Async async = context.async();
        ops.createOrUpdate(new Reconciliation("test-trigger", AssemblyType.CONNECT_S2I, clusterCmNamespace, clusterCmName), clusterCm, Collections.EMPTY_LIST, createResult -> {
            context.assertTrue(createResult.succeeded());

            // Vertify service
            List<Service> capturedServices = serviceCaptor.getAllValues();
            context.assertEquals(1, capturedServices.size());

            // Verify Deployment Config
            List<DeploymentConfig> capturedDc = dcCaptor.getAllValues();
            context.assertEquals(1, capturedDc.size());

            // Verify Build Config
            List<BuildConfig> capturedBc = bcCaptor.getAllValues();
            context.assertEquals(1, capturedBc.size());

            // Verify Image Streams
            List<ImageStream> capturedIs = isCaptor.getAllValues();
            context.assertEquals(2, capturedIs.size());

            // Verify scaleDown / scaleUp were not called
            context.assertEquals(1, dcScaleDownNameCaptor.getAllValues().size());
            context.assertEquals(1, dcScaleUpNameCaptor.getAllValues().size());

            async.complete();
        });
    }

    @Test
    public void testUpdateCluster(TestContext context) {
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        DeploymentConfigOperator mockDcOps = mock(DeploymentConfigOperator.class);
        BuildConfigOperator mockBcOps = mock(BuildConfigOperator.class);
        ImageStreamOperator mockIsOps = mock(ImageStreamOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        ConfigMap clusterCm = ResourceUtils.createEmptyKafkaConnectS2IClusterConfigMap(clusterCmNamespace, clusterCmName);
        KafkaConnectS2ICluster connect = KafkaConnectS2ICluster.fromConfigMap(clusterCm);
        clusterCm.getData().put("image", "some/different:image"); // Change the image to generate some diff

        when(mockCmOps.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockServiceOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateService());
        when(mockDcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateDeploymentConfig());
        when(mockIsOps.get(clusterCmNamespace, connect.getSourceImageStreamName())).thenReturn(connect.generateSourceImageStream());
        when(mockIsOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateTargetImageStream());
        when(mockBcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateBuildConfig());

        ArgumentCaptor<String> serviceNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(serviceNamespaceCaptor.capture(), serviceNameCaptor.capture(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DeploymentConfig> dcCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
        when(mockDcOps.reconcile(dcNamespaceCaptor.capture(), dcNameCaptor.capture(), dcCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleUpNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleUpNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleUpReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleUp(dcScaleUpNamespaceCaptor.capture(), dcScaleUpNameCaptor.capture(), dcScaleUpReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleDownNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleDownNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleDownReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleDown(dcScaleDownNamespaceCaptor.capture(), dcScaleDownNameCaptor.capture(), dcScaleDownReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> isNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> isNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ImageStream> isCaptor = ArgumentCaptor.forClass(ImageStream.class);
        when(mockIsOps.reconcile(isNamespaceCaptor.capture(), isNameCaptor.capture(), isCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> bcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BuildConfig> bcCaptor = ArgumentCaptor.forClass(BuildConfig.class);
        when(mockBcOps.reconcile(bcNamespaceCaptor.capture(), bcNameCaptor.capture(), bcCaptor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectS2IAssemblyOperator ops = new KafkaConnectS2IAssemblyOperator(vertx, true,
                mockCmOps, mockDcOps, mockServiceOps, mockIsOps, mockBcOps, mockSecretOps);

        Async async = context.async();
        ops.createOrUpdate(new Reconciliation("test-trigger", AssemblyType.CONNECT_S2I, clusterCmNamespace, clusterCmName), clusterCm, Collections.EMPTY_LIST, createResult -> {
            context.assertTrue(createResult.succeeded());

            KafkaConnectS2ICluster compareTo = KafkaConnectS2ICluster.fromConfigMap(clusterCm);

            // Vertify service
            List<Service> capturedServices = serviceCaptor.getAllValues();
            context.assertEquals(1, capturedServices.size());
            Service service = capturedServices.get(0);
            context.assertEquals(compareTo.getName(), service.getMetadata().getName());
            context.assertEquals(compareTo.generateService(), service, "Services are not equal");

            // Verify Deployment Config
            List<DeploymentConfig> capturedDc = dcCaptor.getAllValues();
            context.assertEquals(1, capturedDc.size());
            DeploymentConfig dc = capturedDc.get(0);
            context.assertEquals(compareTo.getName(), dc.getMetadata().getName());
            context.assertEquals(compareTo.generateDeploymentConfig(), dc, "Deployment Configs are not equal");

            // Verify Build Config
            List<BuildConfig> capturedBc = bcCaptor.getAllValues();
            context.assertEquals(1, capturedBc.size());
            BuildConfig bc = capturedBc.get(0);
            context.assertEquals(compareTo.getName(), dc.getMetadata().getName());
            context.assertEquals(compareTo.generateBuildConfig(), bc, "Build Configs are not equal");

            // Verify Image Streams
            List<ImageStream> capturedIs = isCaptor.getAllValues();
            context.assertEquals(2, capturedIs.size());
            int sisIndex = (compareTo.getSourceImageStreamName()).equals(capturedIs.get(0).getMetadata().getName()) ? 0 : 1;
            int tisIndex = (compareTo.getName()).equals(capturedIs.get(0).getMetadata().getName()) ? 0 : 1;

            ImageStream sis = capturedIs.get(sisIndex);
            context.assertEquals(compareTo.getSourceImageStreamName(), sis.getMetadata().getName());
            context.assertEquals(compareTo.generateSourceImageStream(), sis, "Source Image Streams are not equal");

            ImageStream tis = capturedIs.get(tisIndex);
            context.assertEquals(compareTo.getName(), tis.getMetadata().getName());
            context.assertEquals(compareTo.generateTargetImageStream(), tis, "Target Image Streams are not equal");

            async.complete();
        });
    }

    @Test
    public void testUpdateClusterFailure(TestContext context) {
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        DeploymentConfigOperator mockDcOps = mock(DeploymentConfigOperator.class);
        BuildConfigOperator mockBcOps = mock(BuildConfigOperator.class);
        ImageStreamOperator mockIsOps = mock(ImageStreamOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        ConfigMap clusterCm = ResourceUtils.createEmptyKafkaConnectS2IClusterConfigMap(clusterCmNamespace, clusterCmName);
        KafkaConnectS2ICluster connect = KafkaConnectS2ICluster.fromConfigMap(clusterCm);
        clusterCm.getData().put("image", "some/different:image"); // Change the image to generate some diff

        when(mockCmOps.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockServiceOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateService());
        when(mockDcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateDeploymentConfig());
        when(mockIsOps.get(clusterCmNamespace, connect.getSourceImageStreamName())).thenReturn(connect.generateSourceImageStream());
        when(mockIsOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateTargetImageStream());
        when(mockBcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateBuildConfig());

        ArgumentCaptor<String> serviceNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Service> serviceCaptor = ArgumentCaptor.forClass(Service.class);
        when(mockServiceOps.reconcile(serviceNamespaceCaptor.capture(), serviceNameCaptor.capture(), serviceCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<DeploymentConfig> dcCaptor = ArgumentCaptor.forClass(DeploymentConfig.class);
        when(mockDcOps.reconcile(dcNamespaceCaptor.capture(), dcNameCaptor.capture(), dcCaptor.capture())).thenReturn(Future.failedFuture("Failed"));

        ArgumentCaptor<String> dcScaleUpNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleUpNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleUpReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleUp(dcScaleUpNamespaceCaptor.capture(), dcScaleUpNameCaptor.capture(), dcScaleUpReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcScaleDownNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcScaleDownNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> dcScaleDownReplicasCaptor = ArgumentCaptor.forClass(Integer.class);
        when(mockDcOps.scaleDown(dcScaleDownNamespaceCaptor.capture(), dcScaleDownNameCaptor.capture(), dcScaleDownReplicasCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> isNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> isNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ImageStream> isCaptor = ArgumentCaptor.forClass(ImageStream.class);
        when(mockIsOps.reconcile(isNamespaceCaptor.capture(), isNameCaptor.capture(), isCaptor.capture())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> bcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bcNameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<BuildConfig> bcCaptor = ArgumentCaptor.forClass(BuildConfig.class);
        when(mockBcOps.reconcile(bcNamespaceCaptor.capture(), bcNameCaptor.capture(), bcCaptor.capture())).thenReturn(Future.succeededFuture());

        KafkaConnectS2IAssemblyOperator ops = new KafkaConnectS2IAssemblyOperator(vertx, true,
                mockCmOps, mockDcOps, mockServiceOps, mockIsOps, mockBcOps, mockSecretOps);

        Async async = context.async();
        ops.createOrUpdate(new Reconciliation("test-trigger", AssemblyType.CONNECT_S2I, clusterCmNamespace, clusterCmName), clusterCm, Collections.EMPTY_LIST, createResult -> {
            context.assertFalse(createResult.succeeded());

            async.complete();
        });
    }

    @Test
    public void testUpdateClusterScaleUp(TestContext context) {
        int scaleTo = 4;
        String newReplicas = String.valueOf(scaleTo);

        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        DeploymentConfigOperator mockDcOps = mock(DeploymentConfigOperator.class);
        BuildConfigOperator mockBcOps = mock(BuildConfigOperator.class);
        ImageStreamOperator mockIsOps = mock(ImageStreamOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        ConfigMap clusterCm = ResourceUtils.createEmptyKafkaConnectS2IClusterConfigMap(clusterCmNamespace, clusterCmName);
        KafkaConnectS2ICluster connect = KafkaConnectS2ICluster.fromConfigMap(clusterCm);
        clusterCm.getData().put(KafkaConnectCluster.KEY_REPLICAS, newReplicas); // Change replicas to create ScaleUp

        when(mockCmOps.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockServiceOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateService());
        when(mockDcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateDeploymentConfig());
        when(mockIsOps.get(clusterCmNamespace, connect.getSourceImageStreamName())).thenReturn(connect.generateSourceImageStream());
        when(mockIsOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateTargetImageStream());
        when(mockBcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateBuildConfig());

        when(mockServiceOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockDcOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleUp(clusterCmNamespace, connect.getName(), scaleTo);

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleDown(clusterCmNamespace, connect.getName(), scaleTo);

        when(mockIsOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockBcOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        KafkaConnectS2IAssemblyOperator ops = new KafkaConnectS2IAssemblyOperator(vertx, true,
                mockCmOps, mockDcOps, mockServiceOps, mockIsOps, mockBcOps, mockSecretOps);

        Async async = context.async();
        ops.createOrUpdate(new Reconciliation("test-trigger", AssemblyType.CONNECT_S2I, clusterCmNamespace, clusterCmName), clusterCm, Collections.EMPTY_LIST, createResult -> {
            context.assertTrue(createResult.succeeded());

            verify(mockDcOps).scaleUp(clusterCmNamespace, connect.getName(), scaleTo);

            async.complete();
        });
    }

    @Test
    public void testUpdateClusterScaleDown(TestContext context) {
        int scaleTo = 2;
        String newReplicas = String.valueOf(scaleTo);

        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        DeploymentConfigOperator mockDcOps = mock(DeploymentConfigOperator.class);
        BuildConfigOperator mockBcOps = mock(BuildConfigOperator.class);
        ImageStreamOperator mockIsOps = mock(ImageStreamOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        ConfigMap clusterCm = ResourceUtils.createEmptyKafkaConnectS2IClusterConfigMap(clusterCmNamespace, clusterCmName);
        KafkaConnectS2ICluster connect = KafkaConnectS2ICluster.fromConfigMap(clusterCm);
        clusterCm.getData().put(KafkaConnectCluster.KEY_REPLICAS, newReplicas); // Change replicas to create ScaleDown

        when(mockCmOps.get(clusterCmNamespace, clusterCmName)).thenReturn(clusterCm);
        when(mockServiceOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateService());
        when(mockDcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateDeploymentConfig());
        when(mockIsOps.get(clusterCmNamespace, connect.getSourceImageStreamName())).thenReturn(connect.generateSourceImageStream());
        when(mockIsOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateTargetImageStream());
        when(mockBcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateBuildConfig());

        when(mockServiceOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockDcOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleUp(clusterCmNamespace, connect.getName(), 2);

        doAnswer(i -> Future.succeededFuture(scaleTo))
                .when(mockDcOps).scaleDown(clusterCmNamespace, connect.getName(), scaleTo);

        when(mockIsOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        when(mockBcOps.reconcile(any(), any(), any())).thenReturn(Future.succeededFuture());

        KafkaConnectS2IAssemblyOperator ops = new KafkaConnectS2IAssemblyOperator(vertx, true,
                mockCmOps, mockDcOps, mockServiceOps, mockIsOps, mockBcOps, mockSecretOps);

        Async async = context.async();
        ops.createOrUpdate(new Reconciliation("test-trigger", AssemblyType.CONNECT_S2I, clusterCmNamespace, clusterCmName), clusterCm, Collections.EMPTY_LIST, createResult -> {
            context.assertTrue(createResult.succeeded());

            // Verify ScaleDown
            verify(mockDcOps).scaleDown(clusterCmNamespace, connect.getName(), scaleTo);

            async.complete();
        });
    }

    @Test
    public void testDeleteCluster(TestContext context) {
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        DeploymentConfigOperator mockDcOps = mock(DeploymentConfigOperator.class);
        BuildConfigOperator mockBcOps = mock(BuildConfigOperator.class);
        ImageStreamOperator mockIsOps = mock(ImageStreamOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);

        String clusterCmName = "foo";
        String clusterCmNamespace = "test";

        KafkaConnectS2ICluster connect = KafkaConnectS2ICluster.fromConfigMap(ResourceUtils.createEmptyKafkaConnectS2IClusterConfigMap(clusterCmNamespace, clusterCmName));

        when(mockDcOps.get(clusterCmNamespace, connect.getName())).thenReturn(connect.generateDeploymentConfig());
        when(mockIsOps.get(clusterCmNamespace, connect.getSourceImageStreamName())).thenReturn(connect.generateSourceImageStream());

        ArgumentCaptor<String> serviceNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> serviceNameCaptor = ArgumentCaptor.forClass(String.class);
        when(mockServiceOps.reconcile(serviceNamespaceCaptor.capture(), serviceNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> dcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dcNameCaptor = ArgumentCaptor.forClass(String.class);
        when(mockDcOps.reconcile(dcNamespaceCaptor.capture(), dcNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> isNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> isNameCaptor = ArgumentCaptor.forClass(String.class);
        when(mockIsOps.reconcile(isNamespaceCaptor.capture(), isNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        ArgumentCaptor<String> bcNamespaceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> bcNameCaptor = ArgumentCaptor.forClass(String.class);
        when(mockBcOps.reconcile(bcNamespaceCaptor.capture(), bcNameCaptor.capture(), isNull())).thenReturn(Future.succeededFuture());

        KafkaConnectS2IAssemblyOperator ops = new KafkaConnectS2IAssemblyOperator(vertx, true,
                mockCmOps, mockDcOps, mockServiceOps, mockIsOps, mockBcOps, mockSecretOps);

        Async async = context.async();
        ops.delete(new Reconciliation("test-trigger", AssemblyType.CONNECT_S2I, clusterCmNamespace, clusterCmName), createResult -> {
            context.assertTrue(createResult.succeeded());

            // Verify service
            context.assertEquals(1, serviceNameCaptor.getAllValues().size());
            context.assertEquals(clusterCmNamespace, serviceNamespaceCaptor.getValue());
            context.assertEquals(connect.getName(), serviceNameCaptor.getValue());

            // Vertify deployment Config
            context.assertEquals(1, dcNameCaptor.getAllValues().size());
            context.assertEquals(clusterCmNamespace, dcNamespaceCaptor.getValue());
            context.assertEquals(connect.getName(), dcNameCaptor.getValue());

            // Vertify BuildConfig
            context.assertEquals(1, bcNameCaptor.getAllValues().size());
            context.assertEquals(clusterCmNamespace, bcNamespaceCaptor.getValue());
            context.assertEquals(connect.getName(), bcNameCaptor.getValue());

            // Vertify ImageStreams
            int sisIndex = (connect.getSourceImageStreamName()).equals(isNameCaptor.getAllValues().get(0)) ? 0 : 1;
            int tisIndex = (connect.getName()).equals(isNameCaptor.getAllValues().get(0)) ? 0 : 1;
            context.assertEquals(2, isNameCaptor.getAllValues().size());
            context.assertEquals(clusterCmNamespace, isNamespaceCaptor.getAllValues().get(sisIndex));
            context.assertEquals(connect.getSourceImageStreamName(), isNameCaptor.getAllValues().get(sisIndex));
            context.assertEquals(clusterCmNamespace, isNamespaceCaptor.getAllValues().get(tisIndex));
            context.assertEquals(connect.getName(), isNameCaptor.getAllValues().get(tisIndex));


            async.complete();
        });
    }

    @Test
    public void testReconcile(TestContext context) {
        ConfigMapOperator mockCmOps = mock(ConfigMapOperator.class);
        ServiceOperator mockServiceOps = mock(ServiceOperator.class);
        DeploymentConfigOperator mockDcOps = mock(DeploymentConfigOperator.class);
        BuildConfigOperator mockBcOps = mock(BuildConfigOperator.class);
        ImageStreamOperator mockIsOps = mock(ImageStreamOperator.class);
        SecretOperator mockSecretOps = mock(SecretOperator.class);


        String clusterCmNamespace = "test";

        ConfigMap foo = ResourceUtils.createEmptyKafkaConnectClusterConfigMap(clusterCmNamespace, "foo");
        ConfigMap bar = ResourceUtils.createEmptyKafkaConnectClusterConfigMap(clusterCmNamespace, "bar");
        ConfigMap baz = ResourceUtils.createEmptyKafkaConnectClusterConfigMap(clusterCmNamespace, "baz");
        when(mockCmOps.list(eq(clusterCmNamespace), any())).thenReturn(asList(foo, bar));
        // when requested ConfigMap for a specific Kafka Connect S2I cluster
        when(mockCmOps.get(eq(clusterCmNamespace), eq("foo"))).thenReturn(foo);
        when(mockCmOps.get(eq(clusterCmNamespace), eq("bar"))).thenReturn(bar);

        // providing the list of ALL DeploymentConfigs for all the Kafka Connect S2I clusters
        Labels newLabels = Labels.forType(AssemblyType.CONNECT_S2I);
        when(mockDcOps.list(eq(clusterCmNamespace), eq(newLabels))).thenReturn(
                asList(KafkaConnectS2ICluster.fromConfigMap(bar).generateDeploymentConfig(),
                        KafkaConnectS2ICluster.fromConfigMap(baz).generateDeploymentConfig()));

        // providing the list DeploymentConfigs for already "existing" Kafka Connect S2I clusters
        Labels barLabels = Labels.forCluster("bar");
        when(mockDcOps.list(eq(clusterCmNamespace), eq(barLabels))).thenReturn(
                asList(KafkaConnectS2ICluster.fromConfigMap(bar).generateDeploymentConfig())
        );

        Labels bazLabels = Labels.forCluster("baz");
        when(mockDcOps.list(eq(clusterCmNamespace), eq(bazLabels))).thenReturn(
                asList(KafkaConnectS2ICluster.fromConfigMap(baz).generateDeploymentConfig())
        );

        Set<String> createdOrUpdated = new HashSet<>();
        Set<String> deleted = new HashSet<>();

        Async async = context.async(3);
        KafkaConnectS2IAssemblyOperator ops = new KafkaConnectS2IAssemblyOperator(vertx, true,
                mockCmOps, mockDcOps, mockServiceOps, mockIsOps, mockBcOps, mockSecretOps) {

            @Override
            public void createOrUpdate(Reconciliation reconciliation, ConfigMap assemblyCm, List<Secret> assemblySecrets, Handler<AsyncResult<Void>> h) {
                createdOrUpdated.add(assemblyCm.getMetadata().getName());
                async.countDown();
                h.handle(Future.succeededFuture());
            }
            @Override
            public void delete(Reconciliation reconciliation, Handler h) {
                deleted.add(reconciliation.assemblyName());
                async.countDown();
                h.handle(Future.succeededFuture());
            }
        };

        // Now try to reconcile all the Kafka Connect S2I clusters
        ops.reconcileAll("test", clusterCmNamespace, Labels.EMPTY);

        async.await();

        context.assertEquals(new HashSet(asList("foo", "bar")), createdOrUpdated);
        context.assertEquals(singleton("baz"), deleted);
    }

}
