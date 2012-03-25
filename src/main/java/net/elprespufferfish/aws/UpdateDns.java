package net.elprespufferfish.aws;

import static java.util.concurrent.TimeUnit.MINUTES;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLConnection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.PropertiesCredentials;
import com.amazonaws.services.route53.AmazonRoute53;
import com.amazonaws.services.route53.AmazonRoute53Client;
import com.amazonaws.services.route53.model.Change;
import com.amazonaws.services.route53.model.ChangeAction;
import com.amazonaws.services.route53.model.ChangeBatch;
import com.amazonaws.services.route53.model.ChangeResourceRecordSetsRequest;
import com.amazonaws.services.route53.model.ListResourceRecordSetsRequest;
import com.amazonaws.services.route53.model.ListResourceRecordSetsResult;
import com.amazonaws.services.route53.model.RRType;
import com.amazonaws.services.route53.model.ResourceRecord;
import com.amazonaws.services.route53.model.ResourceRecordSet;

/**
 * Updates a particular record set to your current external IP address if necessary
 *
 * @author elprespufferfish
 */
public class UpdateDns {

    private static final int NUM_RETRIES = 3;
    private static final int RETRY_DELAY_MS = 5000;

    private static final Long TTL = Long.valueOf(MINUTES.toSeconds(5));

    private static class Arguments {
        @Option(required = true, name = "--hosted-zone", usage = "Route53 Hosted Zone ID", metaVar = "ID")
        public String hostedZoneId;

        @Option(required = true, name = "--record-set", usage = "DNS Record Set to act on", metaVar = "foo.bar.com")
        public String recordSetName;

        @Option(required = true, name = "--aws-credentials-file", usage = "Path to properties file containing your AWS credentials", metaVar = "/path/to/credentials.properties")
        public String awsCredentialsFilePath;
    }

    public static void main(String[] args) throws IOException {
        Arguments arguments = new Arguments();
        CmdLineParser parser = new CmdLineParser(arguments);
        try {
            parser.parseArgument(args);
        } catch (CmdLineException e) {
            System.err.println("Usage: java -jar dyndns.jar <arguments>");
            parser.printUsage(System.err);
            System.exit(1);
        }

        File awsCredentialsFile = new File(arguments.awsCredentialsFilePath);
        AWSCredentials awsCredentials = new PropertiesCredentials(awsCredentialsFile);
        AmazonRoute53 route53 = new AmazonRoute53Client(awsCredentials);

        UpdateDns updateDns = new UpdateDns(route53, arguments.hostedZoneId);
        updateDns.updateDns(arguments.recordSetName);
    }

    private final AmazonRoute53 route53;
    private final String hostedZoneId;

    public UpdateDns(AmazonRoute53 route53, String hostedZoneId) {
        this.route53 = route53;
        this.hostedZoneId = hostedZoneId;
    }

    private void updateDns(String recordSetName) {
        String currentIpAddress = getExternalIpAddress();

        String currentValue = getCurrentAValue(recordSetName);

        if (!currentIpAddress.equals(currentValue)) {
            System.out.println("Updating IP from \"" + currentValue + "\" to \"" + currentIpAddress + "\"");
            updateAValue(recordSetName, currentIpAddress);
        }
    }

    private String getExternalIpAddress() {
        return execWithRetries(new Callable<String>() {
            @Override
            public String call() throws Exception {
                try {
                    URL url = new URL("http://automation.whatismyip.com/n09230945.asp");
                    URLConnection urlConnection = url.openConnection();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(urlConnection.getInputStream(), "UTF-8"))) {
                        String response = reader.readLine();

                        if (InetAddress.getByName(response) == null) {
                            throw new RuntimeException("Invalid response \"" + response + "\"");
                        }

                        return response;
                    }
                } catch (IOException e) {
                    throw new RuntimeException("Could not get current external IP address", e);
                }
            }
        });
    }

    /**
     * TODO: assumes exactly one A record for record set
     * @param recordSetName
     * @return value for A record of record set
     */
    private String getCurrentAValue(final String recordSetName) {
        final ListResourceRecordSetsRequest listResourceRecordSetsRequest = new ListResourceRecordSetsRequest()
                .withHostedZoneId(hostedZoneId)
                .withMaxItems("1")
                .withStartRecordName(recordSetName);
        ListResourceRecordSetsResult listResourceRecordSetsResult = execWithRetries(new Callable<ListResourceRecordSetsResult>() {
            @Override
            public ListResourceRecordSetsResult call() throws Exception {
                return route53.listResourceRecordSets(listResourceRecordSetsRequest);
            }
        });

        List<ResourceRecordSet> resourceRecordSets = listResourceRecordSetsResult.getResourceRecordSets();
        if (resourceRecordSets.isEmpty()) {
            throw new IllegalStateException("Could not find any resource record sets for \"" + recordSetName + "\" in hosted zone \"" + hostedZoneId + "\"");
        }

        for (ResourceRecordSet resourceRecordSet : resourceRecordSets) {
            if (RRType.valueOf(resourceRecordSet.getType()) == RRType.A) {
                List<ResourceRecord> resourceRecords = resourceRecordSet.getResourceRecords();
                if (resourceRecords.isEmpty()) {
                    throw new IllegalStateException("Could not find any A records for set \"" + resourceRecordSet.getName() + "\" in hosted zone \"" + hostedZoneId + "\"");
                }
                return resourceRecords.get(0).getValue();
            }
        }
        throw new IllegalStateException("Could not find any A records for record set \"" + recordSetName + "\"");
    }

    private void updateAValue(final String recordSetName, final String newValue) {
        ResourceRecord resourceRecord = new ResourceRecord()
                .withValue(newValue);

        ResourceRecordSet resourceRecordSet = new ResourceRecordSet()
                .withName(recordSetName)
                .withType(RRType.A)
                .withTTL(TTL)
                .withResourceRecords(Collections.singleton(resourceRecord));

        Change deleteChange = new Change()
                .withAction(ChangeAction.DELETE)
                .withResourceRecordSet(resourceRecordSet);

        Change createChange = new Change()
                .withAction(ChangeAction.CREATE)
                .withResourceRecordSet(resourceRecordSet);

        ChangeBatch changeBatch = new ChangeBatch()
                .withChanges(deleteChange, createChange);

        final ChangeResourceRecordSetsRequest changeResourceRecordSetsRequest = new ChangeResourceRecordSetsRequest()
                .withHostedZoneId(hostedZoneId)
                .withChangeBatch(changeBatch);

        execWithRetries(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                route53.changeResourceRecordSets(changeResourceRecordSetsRequest);
                // TODO - poll until the change as propagated?
                return null;
            }
        });
    }

    private <T> T execWithRetries(Callable<T> c) {
        Exception caughtException = null;
        for (int i = 0; i < NUM_RETRIES; i++) {
            try {
                return c.call();
            } catch (Exception e) {
                caughtException = e;
            }
            try {
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException e) {
                throw new RuntimeException("Could not sleep during retry", e);
            }
        }
        throw new RuntimeException("Could not obtain result after " + NUM_RETRIES + " retries", caughtException);
    }

}
