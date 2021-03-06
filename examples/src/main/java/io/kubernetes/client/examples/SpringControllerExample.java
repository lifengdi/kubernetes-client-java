/*
Copyright 2020 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.kubernetes.client.examples;

import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.models.V1Node;
import io.kubernetes.client.openapi.models.V1NodeList;
import io.kubernetes.client.openapi.models.V1Pod;
import io.kubernetes.client.openapi.models.V1PodList;
import io.kubernetes.client.spring.extended.controller.KubernetesInformerConfigurer;
import io.kubernetes.client.spring.extended.controller.annotation.*;
import io.kubernetes.client.spring.extended.controller.factory.KubernetesControllerFactory;
import io.kubernetes.client.spring.extended.controller.metrics.PrometheusScrapeEndpoint;
import io.kubernetes.client.util.ClientBuilder;
import java.io.IOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class SpringControllerExample {

  public static void main(String[] args) {
    SpringApplication.run(SpringControllerExample.class, args);
  }

  @Configuration
  public static class AppConfig {

    @Bean
    public CommandLineRunner commandLineRunner(
        SharedInformerFactory sharedInformerFactory,
        @Qualifier("node-printing-controller") Controller nodePrintingController) {
      return args -> {
        System.out.println("starting informers..");
        sharedInformerFactory.startAllRegisteredInformers();

        System.out.println("running controller..");
        nodePrintingController.run();
      };
    }

    // *REQUIRED*
    // Configurer components that registers informers to the informer-factory in the context.
    @Bean
    public KubernetesInformerConfigurer kubernetesInformerConfigurer(
        ApiClient apiClient, SharedInformerFactory sharedInformerFactory) {
      return new KubernetesInformerConfigurer(apiClient, sharedInformerFactory);
    }

    // *REQUIRED*
    // factorybean to crete controller
    @Bean("node-printing-controller")
    public KubernetesControllerFactory kubernetesReconcilerConfigurer(
        SharedInformerFactory sharedInformerFactory, Reconciler reconciler) {
      return new KubernetesControllerFactory(sharedInformerFactory, reconciler);
    }

    // *OPTIONAL*
    // Injecting and customize your ApiClient, if not specified, fallbacks to {@link
    // io.kubernetes.client.util.ClientBuilder#standard}
    @Bean
    public ApiClient myApiClient() throws IOException {
      ApiClient apiClient = ClientBuilder.standard().build();
      return apiClient.setHttpClient(
          apiClient.getHttpClient().newBuilder().readTimeout(Duration.ZERO).build());
    }

    // *REQUIRED*
    // Injecting your SharedInformerFactory class annotated `@KubernetesInformers`
    @Bean("sharedInformerFactory")
    public SharedInformerFactory sharedInformerFactory() {
      return new MySharedInformerFactory();
    }

    // *OPTIONAL*
    // Enabling prometheus scraping endpoint at `/actuator/prometheus`
    // SHOULD set `management.endpoints.web.exposure.include=prometheus` property.
    @Bean
    public PrometheusScrapeEndpoint prometheusScrapeEndpoint() {
      return new PrometheusScrapeEndpoint();
    }
  }

  @KubernetesInformers({ // Defining what resources is the informer-factory actually watching.
    @KubernetesInformer(
        apiTypeClass = V1Node.class,
        apiListTypeClass = V1NodeList.class,
        groupVersionResource =
            @GroupVersionResource(apiGroup = "", apiVersion = "v1", resourcePlural = "nodes"),
        resyncPeriodMillis = 60 * 1000L),
    @KubernetesInformer(
        apiTypeClass = V1Pod.class,
        apiListTypeClass = V1PodList.class,
        groupVersionResource =
            @GroupVersionResource(apiGroup = "", apiVersion = "v1", resourcePlural = "pods")),
  })
  public static class MySharedInformerFactory extends SharedInformerFactory {}

  // As long as a reconciler bean attached `@KubernetesReconciler` detected in the context, we
  // will
  // be automatically creating a conresponding controller bean implementing {@link
  // io.kubernetes.client.extended.controller.Controller}
  // with the name specified and registering it to the spring bean-factory.
  @KubernetesReconciler(
      watches =
          @KubernetesReconcilerWatches({
            @KubernetesReconcilerWatch(
                apiTypeClass = V1Node.class,
                resyncPeriodMillis = 60 * 1000L // fully resync every 1 minute
                ),
          }))
  @Component
  public static class NodePrintingReconciler implements Reconciler {

    @Value("${namespace}")
    private String namespace;

    @Autowired private SharedInformer<V1Node> nodeInformer;
    @Autowired private SharedInformer<V1Pod> podInformer;
    @Autowired private Lister<V1Node> nodeLister;
    @Autowired private Lister<V1Pod> podLister;

    // *OPTIONAL*
    // If you feed like hold the controller from running util some condition..
    @KubernetesReconcilerReadyFunc
    public boolean informerReady() {
      return podInformer.hasSynced() && nodeInformer.hasSynced();
    }

    @Override
    public Result reconcile(Request request) {
      V1Node node = nodeLister.get(request.getName());

      System.out.println("get all pods in namespace " + namespace);
      podLister.namespace(namespace).list().stream()
          .map(pod -> pod.getMetadata().getName())
          .forEach(System.out::println);

      System.out.println("triggered reconciling " + node.getMetadata().getName());
      return new Result(false);
    }
  }
}
