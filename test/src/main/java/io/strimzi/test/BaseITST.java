/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.test;

import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.strimzi.test.k8s.KubeClient;
import io.strimzi.test.k8s.KubeClusterResource;
import io.strimzi.test.timemeasuring.Operation;
import io.strimzi.test.timemeasuring.TimeMeasuringSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class BaseITST {

    private static final String CO_INSTALL_DIR = "../install/cluster-operator";

    private static final Logger LOGGER = LogManager.getLogger(BaseITST.class);
    protected static final String CLUSTER_NAME = "my-cluster";

    public static final KubeClusterResource CLUSTER = new KubeClusterResource();
    protected static final DefaultKubernetesClient CLIENT = new DefaultKubernetesClient();
    public static final KubeClient<?> KUBE_CLIENT = CLUSTER.client();

    private List<String> deploymentResources = new ArrayList<>();
    private List<String> deploymentNamespaces = new ArrayList<>();
    private Map<File, String> clusterOperatorMap = new HashMap<>();

    protected String testClass;
    protected String testName;

    /**
     * Perform application of ServiceAccount, Roles and CRDs needed for proper cluster operator deployment.
     * Configuration files are loaded from install/cluster-operator directory.
     */
    protected void applyClusterOperatorInstallFiles() {
        TimeMeasuringSystem.setTestName(testClass, testClass);
        TimeMeasuringSystem.startOperation(Operation.CO_CREATION);
        Map<File, String> operatorFiles = Arrays.stream(new File(CO_INSTALL_DIR).listFiles()).sorted().filter(file ->
                !file.getName().matches(".*(Binding|Deployment)-.*")
        ).collect(Collectors.toMap(file -> file, f -> TestUtils.getContent(f, TestUtils::toYamlString), (x, y) -> x, LinkedHashMap::new));
        for (Map.Entry<File, String> entry : operatorFiles.entrySet()) {
            LOGGER.info("Applying configuration file: {}", entry.getKey());
            clusterOperatorMap.put(entry.getKey(), entry.getValue());
            KUBE_CLIENT.clientWithAdmin().applyContent(entry.getValue());
        }
        LOGGER.info(clusterOperatorMap.keySet().toString());
        TimeMeasuringSystem.stopOperation(Operation.CO_CREATION);
    }

    /**
     * Delete ServiceAccount, Roles and CRDs from kubernetes cluster.
     */
    protected void deleteClusterOperatorInstallFiles() {
        TimeMeasuringSystem.setTestName(testClass, testClass);
        TimeMeasuringSystem.startOperation(Operation.CO_DELETION);
        LOGGER.info(clusterOperatorMap.keySet().toString());
        for (Map.Entry<File, String> entry : clusterOperatorMap.entrySet()) {
            LOGGER.info("Removing configuration file: {}", entry.getKey());
            KUBE_CLIENT.clientWithAdmin().deleteContent(entry.getValue());
            clusterOperatorMap.remove(entry.getKey());
        }
        TimeMeasuringSystem.stopOperation(Operation.CO_DELETION);
    }

    /**
     * Create namespaces for test resources.
     * @param useNamespace namespace which will be used as default by kubernetes client
     * @param namespaces list of namespaces which will be created
     */
    protected void createNamespaces(String useNamespace, List<String> namespaces) {
        LOGGER.info(namespaces);
        for (String namespace: namespaces) {
            LOGGER.info("Creating namespace: {}", namespace);
            deploymentNamespaces.add(namespace);
            KUBE_CLIENT.createNamespace(namespace);
            KUBE_CLIENT.waitForResourceCreation("Namespace", namespace);
            LOGGER.info("Namespace {} created", namespace);
        }
        LOGGER.info("Using namespace {}", useNamespace);
        KUBE_CLIENT.namespace(useNamespace);
        LOGGER.info("Created namespaces: {}", deploymentNamespaces);
    }

    /**
     * Create namespace for test resources. Deletion is up to caller and can be managed
     * by calling {@link #deleteNamespaces()} or {@link #teardownEnvForOperator()}
     * @param useNamespace namespace which will be created and used as default by kubernetes client
     */
    protected void createNamespace(String useNamespace) {
        createNamespaces(useNamespace, Collections.singletonList(useNamespace));
    }

    /**
     * Delete all created namespaces. Namespaces are deleted in the reverse order than they were created.
     */
    protected void deleteNamespaces() {
        LOGGER.info("Going to delete following namespaces: {}", deploymentNamespaces);
        Collections.reverse(deploymentNamespaces);
        LOGGER.info("Going to delete following namespaces: {}", deploymentNamespaces);
        for (String namespace: deploymentNamespaces) {
            LOGGER.info("Deleting namespace: {}", namespace);
            KUBE_CLIENT.deleteNamespace(namespace);
            KUBE_CLIENT.waitForResourceDeletion("Namespace", namespace);
            LOGGER.info("Namespace {} deleted", namespace);
            deploymentNamespaces.remove(namespace);
        }
        LOGGER.info("Using namespace {}", CLUSTER.defaultNamespace());
        KUBE_CLIENT.namespace(CLUSTER.defaultNamespace());
    }

    /**
     * Apply custom resources for CO such as templates. Deletion is up to caller and can be managed
     * by calling {@link #deleteCustomResources()}
     * @param resources array of paths to yaml files with resources specifications
     */
    protected void createCustomResources(String... resources) {
        for (String resource : resources) {
            LOGGER.info("Creating resources {}", resource);
            deploymentResources.add(resource);
            KUBE_CLIENT.clientWithAdmin().create(resource);
        }
        LOGGER.info("Custom resources: {}", deploymentResources);
    }

    /**
     * Delete custom resources such as templates. Resources are deleted in the reverse order than they were created.
     */
    protected void deleteCustomResources() {
        Collections.reverse(deploymentResources);
        LOGGER.info("Custom resources for delete: {}", deploymentResources);
        for (String resource : deploymentResources) {
            LOGGER.info("Deleting resources {}", resource);
            KUBE_CLIENT.delete(resource);
            deploymentResources.remove(resource);
        }
        LOGGER.info("Custom resources after delete: {}", deploymentResources);
    }

    /**
     * Delete custom resources such as templates. Resources are deleted in the reverse order than they were created.
     */
    protected void deleteCustomResources(String... resources) {
        for (String resource : resources) {
            LOGGER.info("Deleting resources {}", resource);
            KUBE_CLIENT.delete(resource);
            deploymentResources.remove(resource);
        }
    }

    /**
     * Prepare environment for cluster operator which includes creation of namespaces, custom resources and operator
     * specific config files such as ServiceAccount, Roles and CRDs.
     * @param clientNamespace namespace which will be created and used as default by kube client
     * @param namespaces list of namespaces which will be created
     * @param resources list of path to yaml files with resources specifications
     */
    protected void prepareEnvForOperator(String clientNamespace, List<String> namespaces, String... resources) {
        createNamespaces(clientNamespace, namespaces);
        createCustomResources(resources);
        applyClusterOperatorInstallFiles();
    }

    /**
     * Prepare environment for cluster operator which includes creation of namespaces, custom resources and operator
     * specific config files such as ServiceAccount, Roles and CRDs.
     * @param clientNamespace namespace which will be created and used as default by kube client
     * @param resources list of path to yaml files with resources specifications
     */
    protected void prepareEnvForOperator(String clientNamespace, String... resources) {
        prepareEnvForOperator(clientNamespace, Collections.singletonList(clientNamespace), resources);
    }

    /**
     * Prepare environment for cluster operator which includes creation of namespaces, custom resources and operator
     * specific config files such as ServiceAccount, Roles and CRDs.
     * @param clientNamespace namespace which will be created and used as default by kube client
     */
    protected void prepareEnvForOperator(String clientNamespace) {
        prepareEnvForOperator(clientNamespace, Collections.singletonList(clientNamespace));
    }

    /**
     * Clear cluster from all created namespaces and configurations files for cluster operator.
     */
    protected void teardownEnvForOperator() {
        deleteClusterOperatorInstallFiles();
        deleteCustomResources();
        deleteNamespaces();
    }

    @BeforeEach
    void setTestName(TestInfo testInfo) {
        testName = testInfo.getTestMethod().get().getName();
    }

    @BeforeAll
    void createTestClassResources(TestInfo testInfo) {
        testClass = testInfo.getTestClass().get().getSimpleName();
    }
}
