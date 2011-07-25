/**
 *
 * Copyright (C) 2011 Cloud Conscious, LLC. <info@cloudconscious.com>
 *
 * ====================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ====================================================================
 */
package org.jclouds.gogrid;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.String.format;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;

import org.jclouds.Constants;
import org.jclouds.compute.ComputeServiceContextFactory;
import org.jclouds.domain.Credentials;
import org.jclouds.gogrid.domain.Ip;
import org.jclouds.gogrid.domain.IpPortPair;
import org.jclouds.gogrid.domain.Job;
import org.jclouds.gogrid.domain.LoadBalancer;
import org.jclouds.gogrid.domain.LoadBalancerPersistenceType;
import org.jclouds.gogrid.domain.LoadBalancerType;
import org.jclouds.gogrid.domain.PowerCommand;
import org.jclouds.gogrid.domain.Server;
import org.jclouds.gogrid.domain.ServerImage;
import org.jclouds.gogrid.domain.ServerImageType;
import org.jclouds.gogrid.options.AddLoadBalancerOptions;
import org.jclouds.gogrid.options.AddServerOptions;
import org.jclouds.gogrid.options.GetImageListOptions;
import org.jclouds.gogrid.predicates.LoadBalancerLatestJobCompleted;
import org.jclouds.gogrid.predicates.ServerLatestJobCompleted;
import org.jclouds.http.handlers.BackoffLimitedRetryHandler;
import org.jclouds.logging.log4j.config.Log4JLoggingModule;
import org.jclouds.net.IPSocket;
import org.jclouds.predicates.InetSocketAddressConnect;
import org.jclouds.predicates.RetryablePredicate;
import org.jclouds.rest.RestContext;
import org.jclouds.ssh.SshClient;
import org.jclouds.sshj.SshjSshClient;
import org.testng.SkipException;
import org.testng.TestException;
import org.testng.annotations.AfterTest;
import org.testng.annotations.BeforeGroups;
import org.testng.annotations.Test;

import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.inject.Module;

/**
 * End to end live test for GoGrid
 * <p/>
 * Takes too long to execute.  Please split into multiple tests
 * 
 * @author Oleksiy Yarmula
 */
// NOTE:without testName, this will not call @Before* and fail w/NPE during surefire
@Test(enabled = false, groups = "live", testName = "GoGridLiveTestDisabled")
public class GoGridLiveTestDisabled {

   private GoGridClient client;

   private RetryablePredicate<Server> serverLatestJobCompleted;
   private RetryablePredicate<LoadBalancer> loadBalancerLatestJobCompleted;
   /**
    * Keeps track of the servers, created during the tests, to remove them after all tests complete
    */
   private List<String> serversToDeleteAfterTheTests = new ArrayList<String>();
   private List<String> loadBalancersToDeleteAfterTest = new ArrayList<String>();

   private RestContext<GoGridClient, GoGridAsyncClient> context;
   protected String provider = "gogrid";
   protected String identity;
   protected String credential;
   protected String endpoint;
   protected String apiversion;

   protected void setupCredentials() {
      identity = checkNotNull(System.getProperty("test." + provider + ".identity"), "test." + provider + ".identity");
      credential = checkNotNull(System.getProperty("test." + provider + ".credential"), "test." + provider
               + ".credential");
      endpoint = System.getProperty("test." + provider + ".endpoint");
      apiversion = System.getProperty("test." + provider + ".apiversion");
   }

   protected Properties setupProperties() {
      Properties overrides = new Properties();
      overrides.setProperty(Constants.PROPERTY_TRUST_ALL_CERTS, "true");
      overrides.setProperty(Constants.PROPERTY_RELAX_HOSTNAME, "true");
      overrides.setProperty(provider + ".identity", identity);
      overrides.setProperty(provider + ".credential", credential);
      if (endpoint != null)
         overrides.setProperty(provider + ".endpoint", endpoint);
      if (apiversion != null)
         overrides.setProperty(provider + ".apiversion", apiversion);
      return overrides;
   }

   @BeforeGroups(groups = { "live" })
   public void setupClient() {
      setupCredentials();
      Properties overrides = setupProperties();
      context = new ComputeServiceContextFactory().createContext(provider, ImmutableSet.<Module> of(new Log4JLoggingModule()),
               overrides).getProviderSpecificContext();

      client = context.getApi();
      serverLatestJobCompleted = new RetryablePredicate<Server>(new ServerLatestJobCompleted(client.getJobServices()),
               800, 20, TimeUnit.SECONDS);
      loadBalancerLatestJobCompleted = new RetryablePredicate<LoadBalancer>(new LoadBalancerLatestJobCompleted(client
               .getJobServices()), 800, 20, TimeUnit.SECONDS);
   }

   @Test(enabled = true)
   public void testDescriptionIs500Characters() {
      final String nameOfServer = "Description" + String.valueOf(new Date().getTime()).substring(6);
      serversToDeleteAfterTheTests.add(nameOfServer);

      Set<Ip> availableIps = client.getIpServices().getUnassignedPublicIpList();
      Ip availableIp = Iterables.getLast(availableIps);

      String ram = Iterables.get(client.getServerServices().getRamSizes(), 0).getName();
      StringBuilder builder = new StringBuilder();

      for (int i = 0; i < 1 * 500; i++)
         builder.append('a');

      String description = builder.toString();

      Server createdServer = client.getServerServices().addServer(nameOfServer,
               "GSI-f8979644-e646-4711-ad58-d98a5fa3612c", ram, availableIp.getIp(),
               new AddServerOptions().withDescription(description));
      assertNotNull(createdServer);
      assert serverLatestJobCompleted.apply(createdServer);

      assertEquals(Iterables.getLast(client.getServerServices().getServersByName(nameOfServer)).getDescription(),
               description);

   }

   /**
    * Tests server start, reboot and deletion. Also verifies IP services and job services.
    */
   @Test(enabled = true)
   public void testServerLifecycle() {
      int serverCountBeforeTest = client.getServerServices().getServerList().size();

      final String nameOfServer = "Server" + String.valueOf(new Date().getTime()).substring(6);
      serversToDeleteAfterTheTests.add(nameOfServer);

      Set<Ip> availableIps = client.getIpServices().getUnassignedPublicIpList();
      Ip availableIp = Iterables.getLast(availableIps);

      String ram = Iterables.get(client.getServerServices().getRamSizes(), 0).getName();

      Server createdServer = client.getServerServices().addServer(nameOfServer,
               "GSI-f8979644-e646-4711-ad58-d98a5fa3612c", ram, availableIp.getIp());
      assertNotNull(createdServer);
      assert serverLatestJobCompleted.apply(createdServer);

      // get server by name
      Set<Server> response = client.getServerServices().getServersByName(nameOfServer);
      assert (response.size() == 1);

      // restart the server
      client.getServerServices().power(nameOfServer, PowerCommand.RESTART);

      Set<Job> jobs = client.getJobServices().getJobsForObjectName(nameOfServer);
      assert ("RestartVirtualServer".equals(Iterables.getLast(jobs).getCommand().getName()));

      assert serverLatestJobCompleted.apply(createdServer);

      int serverCountAfterAddingOneServer = client.getServerServices().getServerList().size();
      assert serverCountAfterAddingOneServer == serverCountBeforeTest + 1 : "There should be +1 increase in the number of servers since the test started";

      // delete the server
      client.getServerServices().deleteByName(nameOfServer);

      jobs = client.getJobServices().getJobsForObjectName(nameOfServer);
      assert ("DeleteVirtualServer".equals(Iterables.getLast(jobs).getCommand().getName()));

      assert serverLatestJobCompleted.apply(createdServer);

      int serverCountAfterDeletingTheServer = client.getServerServices().getServerList().size();
      assert serverCountAfterDeletingTheServer == serverCountBeforeTest : "There should be the same # of servers as since the test started";

      // make sure that IP is put back to "unassigned"
      assert client.getIpServices().getUnassignedIpList().contains(availableIp);
   }

   /**
    * Starts a servers, verifies that jobs are created correctly and an be retrieved from the job
    * services
    */
   @Test(dependsOnMethods = "testServerLifecycle", enabled = true)
   public void testJobs() {
      final String nameOfServer = "Server" + String.valueOf(new Date().getTime()).substring(6);
      serversToDeleteAfterTheTests.add(nameOfServer);

      Set<Ip> availableIps = client.getIpServices().getUnassignedPublicIpList();

      String ram = Iterables.get(client.getServerServices().getRamSizes(), 0).getName();

      Server createdServer = client.getServerServices().addServer(nameOfServer,
               "GSI-f8979644-e646-4711-ad58-d98a5fa3612c", ram, Iterables.getLast(availableIps).getIp());

      assert serverLatestJobCompleted.apply(createdServer);

      // restart the server
      client.getServerServices().power(nameOfServer, PowerCommand.RESTART);

      Set<Job> jobs = client.getJobServices().getJobsForObjectName(nameOfServer);

      Job latestJob = Iterables.getLast(jobs);
      Long latestJobId = latestJob.getId();

      Job latestJobFetched = Iterables.getOnlyElement(client.getJobServices().getJobsById(latestJobId));

      assert latestJob.equals(latestJobFetched) : "Job and its reprentation found by ID don't match";

      long[] idsOfAllJobs = new long[jobs.size()];
      int i = 0;
      for (Job job : jobs) {
         idsOfAllJobs[i++] = job.getId();
      }

      Set<Job> jobsFetched = client.getJobServices().getJobsById(idsOfAllJobs);
      assert jobsFetched.size() == jobs.size() : format(
               "Number of jobs fetched by ids doesn't match the number of jobs "
                        + "requested. Requested/expected: %d. Found: %d.", jobs.size(), jobsFetched.size());

      // delete the server
      client.getServerServices().deleteByName(nameOfServer);
   }

   /**
    * Tests common load balancer operations. Also verifies IP services and job services.
    */
   @Test(enabled = true)
   public void testLoadBalancerLifecycle() {
      int lbCountBeforeTest = client.getLoadBalancerServices().getLoadBalancerList().size();

      final String nameOfLoadBalancer = "LoadBalancer" + String.valueOf(new Date().getTime()).substring(6);
      loadBalancersToDeleteAfterTest.add(nameOfLoadBalancer);

      Set<Ip> availableIps = client.getIpServices().getUnassignedPublicIpList();

      if (availableIps.size() < 4)
         throw new SkipException("Not enough available IPs (4 needed) to run the test");
      Iterator<Ip> ipIterator = availableIps.iterator();
      Ip vip = ipIterator.next();
      Ip realIp1 = ipIterator.next();
      Ip realIp2 = ipIterator.next();
      Ip realIp3 = ipIterator.next();

      AddLoadBalancerOptions options = new AddLoadBalancerOptions.Builder().create(LoadBalancerType.LEAST_CONNECTED,
               LoadBalancerPersistenceType.SOURCE_ADDRESS);
      LoadBalancer createdLoadBalancer = client.getLoadBalancerServices().addLoadBalancer(nameOfLoadBalancer,
               new IpPortPair(vip, 80), Arrays.asList(new IpPortPair(realIp1, 80), new IpPortPair(realIp2, 80)),
               options);
      assertNotNull(createdLoadBalancer);
      assert loadBalancerLatestJobCompleted.apply(createdLoadBalancer);

      // get load balancer by name
      Set<LoadBalancer> response = client.getLoadBalancerServices().getLoadBalancersByName(nameOfLoadBalancer);
      assert (response.size() == 1);
      createdLoadBalancer = Iterables.getOnlyElement(response);
      assertNotNull(createdLoadBalancer.getRealIpList());
      assertEquals(createdLoadBalancer.getRealIpList().size(), 2);
      assertNotNull(createdLoadBalancer.getVirtualIp());
      assertEquals(createdLoadBalancer.getVirtualIp().getIp().getIp(), vip.getIp());

      LoadBalancer editedLoadBalancer = client.getLoadBalancerServices().editLoadBalancerNamed(nameOfLoadBalancer,
               Arrays.asList(new IpPortPair(realIp3, 8181)));
      assert loadBalancerLatestJobCompleted.apply(editedLoadBalancer);
      assertNotNull(editedLoadBalancer.getRealIpList());
      assertEquals(editedLoadBalancer.getRealIpList().size(), 1);
      assertEquals(Iterables.getOnlyElement(editedLoadBalancer.getRealIpList()).getIp().getIp(), realIp3.getIp());

      int lbCountAfterAddingOneServer = client.getLoadBalancerServices().getLoadBalancerList().size();
      assert lbCountAfterAddingOneServer == lbCountBeforeTest + 1 : "There should be +1 increase in the number of load balancers since the test started";

      // delete the load balancer
      client.getLoadBalancerServices().deleteByName(nameOfLoadBalancer);

      Set<Job> jobs = client.getJobServices().getJobsForObjectName(nameOfLoadBalancer);
      assert ("DeleteLoadBalancer".equals(Iterables.getLast(jobs).getCommand().getName()));

      assert loadBalancerLatestJobCompleted.apply(createdLoadBalancer);

      int lbCountAfterDeletingTheServer = client.getLoadBalancerServices().getLoadBalancerList().size();
      assert lbCountAfterDeletingTheServer == lbCountBeforeTest : "There should be the same # of load balancers as since the test started";
   }

   /**
    * Tests common server image operations.
    */
   @Test(enabled = true)
   public void testImageLifecycle() {
      GetImageListOptions options = new GetImageListOptions.Builder().publicDatabaseServers();
      Set<ServerImage> images = client.getImageServices().getImageList(options);

      Predicate<ServerImage> isDatabaseServer = new Predicate<ServerImage>() {
         @Override
         public boolean apply(@Nullable ServerImage serverImage) {
            return checkNotNull(serverImage).getType() == ServerImageType.DATABASE_SERVER;
         }
      };

      assert Iterables.all(images, isDatabaseServer) : "All of the images should've been of database type";

      ServerImage image = Iterables.getLast(images);
      ServerImage imageFromServer = Iterables
               .getOnlyElement(client.getImageServices().getImagesByName(image.getName()));
      assertEquals(image, imageFromServer);

      try {
         client.getImageServices().editImageDescription(image.getName(), "newDescription");
         throw new TestException("An exception hasn't been thrown where expected; expected GoGridResponseException");
      } catch (GoGridResponseException e) {
         // expected situation - check and proceed
         assertTrue(e.getMessage().contains("GoGridIllegalArgumentException"));
      }

   }

   @Test(enabled = true)
   public void testShellAccess() throws IOException {
      final String nameOfServer = "Server" + String.valueOf(new Date().getTime()).substring(6);
      serversToDeleteAfterTheTests.add(nameOfServer);

      Set<Ip> availableIps = client.getIpServices().getUnassignedIpList();
      Ip availableIp = Iterables.getLast(availableIps);

      Server createdServer = client.getServerServices().addServer(nameOfServer,
               "GSI-f8979644-e646-4711-ad58-d98a5fa3612c", "1", availableIp.getIp());
      assertNotNull(createdServer);
      assert serverLatestJobCompleted.apply(createdServer);

      // get server by name
      Set<Server> response = client.getServerServices().getServersByName(nameOfServer);
      assert (response.size() == 1);
      createdServer = Iterables.getOnlyElement(response);

      Map<String, Credentials> credsMap = client.getServerServices().getServerCredentialsList();
      Credentials instanceCredentials = credsMap.get(createdServer.getName());
      assertNotNull(instanceCredentials);

      IPSocket socket = new IPSocket(createdServer.getIp().getIp(), 22);

      RetryablePredicate<IPSocket> socketOpen = new RetryablePredicate<IPSocket>(new InetSocketAddressConnect(), 180,
               5, TimeUnit.SECONDS);

      socketOpen.apply(socket);

      SshClient sshClient = new SshjSshClient(new BackoffLimitedRetryHandler(), socket, 60000,
               instanceCredentials.identity, instanceCredentials.credential, null);
      sshClient.connect();
      String output = sshClient.exec("df").getOutput();
      assertTrue(output.contains("Filesystem"),
               "The output should've contained filesystem information, but it didn't. Output: " + output);
      sshClient.disconnect();

      // check that the get credentials call is the same as this
      assertEquals(client.getServerServices().getServerCredentials(createdServer.getId()), instanceCredentials);

      try {
         assertEquals(client.getServerServices().getServerCredentials(Long.MAX_VALUE), null);
      } catch (AssertionError e) {
         e.printStackTrace();
      }

      // delete the server
      client.getServerServices().deleteByName(nameOfServer);
   }

   /**
    * In case anything went wrong during the tests, removes the objects created in the tests.
    */
   @AfterTest
   public void cleanup() {
      for (String serverName : serversToDeleteAfterTheTests) {
         try {
            client.getServerServices().deleteByName(serverName);
         } catch (Exception e) {
            // it's already been deleted - proceed
         }
      }
      for (String loadBalancerName : loadBalancersToDeleteAfterTest) {
         try {
            client.getLoadBalancerServices().deleteByName(loadBalancerName);
         } catch (Exception e) {
            // it's already been deleted - proceed
         }
      }

   }

}
