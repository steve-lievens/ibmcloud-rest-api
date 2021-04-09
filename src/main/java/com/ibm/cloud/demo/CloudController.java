package com.ibm.cloud.demo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import com.ibm.cloud.ce.GetKubeconfigOptions;
import com.ibm.cloud.ce.IbmCloudCodeEngine;
import com.ibm.cloud.sdk.core.security.IamAuthenticator;
import com.ibm.cloud.sdk.core.http.Response;

import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.json.JSONObject;
import io.fabric8.knative.client.DefaultKnativeClient;
import io.fabric8.knative.client.KnativeClient;
import io.fabric8.knative.serving.v1.Service;
import io.fabric8.knative.serving.v1.ServiceBuilder;
import io.fabric8.knative.serving.v1.ServiceList;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder;
import io.fabric8.kubernetes.client.Config;

@RestController
public class CloudController {
    private final Logger logger = Logger.getLogger(CloudController.class.getName());

    // Get the values from the application.properties (and as such from the env
    // vars)
    @Value("${cloud.ibm.apikey}")
    private String ibmcloud_apikey;
    @Value("${cloud.ibm.region}")
    private String ibmcloud_region;
    @Value("${cloud.ibm.ce.projectid}")
    private String ibmcloud_ce_projectid;

    @RequestMapping("/login")
    public String cloudLogin() throws IOException {
        logger.info("Starting cloudLogin ...");
        logger.info("apikey = " + ibmcloud_apikey);

        // Create an IAM authenticator.
        IamAuthenticator authenticator = new IamAuthenticator(ibmcloud_apikey);
        authenticator.setClientIdAndSecret("bx", "bx");

        // Construct the Code Engine Client
        IbmCloudCodeEngine ceClient = new IbmCloudCodeEngine("Code Engine Client", authenticator);
        ceClient.setServiceUrl("https://api." + ibmcloud_region + ".codeengine.cloud.ibm.com/api/v1");

        // Get an IAM delegated refresh token using an HTTP client
        logger.info("Requesting the access token ...");
        URL iamUrl = new URL("https://iam.cloud.ibm.com/identity/token?"
                + "grant_type=urn:ibm:params:oauth:grant-type:apikey&" + "response_type=delegated_refresh_token&"
                + "receiver_client_ids=ce&" + "delegated_refresh_token_expiry=3600&" + "apikey=" + ibmcloud_apikey);
        HttpURLConnection iamConnection = (HttpURLConnection) iamUrl.openConnection();
        iamConnection.setRequestMethod("POST");
        iamConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        BufferedReader iamInput = new BufferedReader(new InputStreamReader(iamConnection.getInputStream()));
        String iamResponse = "";
        String iamInputLine = "";
        while ((iamInputLine = iamInput.readLine()) != null) {
            iamResponse = iamResponse + iamInputLine;
        }
        iamInput.close();
        JSONObject iamJson = new JSONObject(iamResponse);
        String delegatedRefreshToken = iamJson.getString("delegated_refresh_token");

        // Get Code Egnine project config using the Code Engine Client
        GetKubeconfigOptions options = new GetKubeconfigOptions.Builder().id(ibmcloud_ce_projectid)
                .xDelegatedRefreshToken(delegatedRefreshToken).build();
        Response<String> kubeConfigResponse = ceClient.getKubeconfig(options).execute();

        // Setup Kubernetes client using the project config
        String kubeConfigString = kubeConfigResponse.getResult();
        logger.info("Kubeconfig string = " + kubeConfigString);

        /*
         * Reader kubeConfigReader = new StringReader(kubeConfigString); KubeConfig
         * config = KubeConfig.loadKubeConfig(kubeConfigReader); ApiClient client =
         * Config.fromConfig(config); Configuration.setDefaultApiClient(client);
         * 
         * // Get something from project. CoreV1Api api = new CoreV1Api();
         * V1ConfigMapList configMapList =
         * api.listNamespacedConfigMap(config.getNamespace(), null, null, null, null,
         * null, null, null, null, null);
         * 
         * 
         * logger.info("Project " + ibmcloud_ce_projectid + " has " +
         * configMapList.getItems().size() + " configmaps.");
         */

        // Create a config object from the Kubeconfig string
        Config fabric8Config = Config.fromKubeconfig(kubeConfigString);

        // Fire up the Knative client (using this directly instead of Kubernetes client
        // as code engine is actually Knative Serving)
        try (KnativeClient kn = new DefaultKnativeClient(fabric8Config)) {
            // Get all Service objects (which is an Application within Code Engine)
            ServiceList services = kn.services().list();

            // Iterate through list and print names
            logger.info("Printing a list of exising Code Engine Applications :");
            for (Service svc : services.getItems()) {
                System.out.println(svc.getMetadata().getName());
                logger.info("Application : " + svc.getMetadata().getName());
            }

            // Prepare a couple of properties
            Map<String, String> annotations = new HashMap<>();
            annotations.put("autoscaling.knative.dev/minScale", "1");
            annotations.put("autoscaling.knative.dev/maxScale", "1");

            Long containerConcurrency = 100L;
            Long timeoutSeconds = 300L;
            
            Map<String, Quantity> resreqslims = new HashMap<>();
            Quantity cpu = Quantity.parse("100m");
            Quantity mem = Quantity.parse("256M");
            resreqslims.put("cpu", cpu);
            resreqslims.put("memory", mem);
            
            ResourceRequirements resources = new ResourceRequirementsBuilder()
                                                    .addToRequests(resreqslims)
                                                    .addToLimits(resreqslims)
                                                    .build();

            // Create Service object
            logger.info("Creating a new app");
            Service service = new ServiceBuilder()
                .withNewMetadata()
                    .withName("rest-api-test2")
                .endMetadata()
                .withNewSpec()
                    .withNewTemplate()
                        .withNewMetadata()
                            .withAnnotations(annotations)
                        .endMetadata()
                        .withNewSpec()
                            .withContainerConcurrency(containerConcurrency)
                            .addToContainers(new ContainerBuilder()
                                .withImage("docker.io/ibmcom/codeengine")
                                .addNewEnv()
                                    .withName("TARGET").withValue("TEST")
                                .endEnv().withResources(resources)
                                .build())
                            .withTimeoutSeconds(timeoutSeconds)
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

            // Apply it onto Kubernetes Server
            kn.services().create(service);
            logger.info("App created");
            
            // Get all Service objects (which is an Application within Code Engine)
            services = kn.services().list();

            // Iterate through list and print names
            logger.info("Printing a list of exising Code Engine Applications :");
            for (Service svc : services.getItems()) {
                System.out.println(svc.getMetadata().getName());
                logger.info("Application : " + svc.getMetadata().getName());
            }

        }

        return "Made it !";
    }

}
