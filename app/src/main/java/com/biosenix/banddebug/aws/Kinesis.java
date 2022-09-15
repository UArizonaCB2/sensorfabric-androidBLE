package com.biosenix.banddebug.aws;

import android.util.Log;

import com.biosenix.banddebug.models.Acceleration;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.ExecutionException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kinesis.KinesisAsyncClient;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamRequest;
import software.amazon.awssdk.services.kinesis.model.DescribeStreamResponse;
import software.amazon.awssdk.services.kinesis.model.PutRecordRequest;

public class Kinesis {
    private static ObjectMapper JSON = new ObjectMapper();
    static {
        JSON.configure(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES, false);
    }

    private String streamName;
    private Region region;
    private KinesisAsyncClient client;

    // TODO : Use your own AWS credentials.
    private final String accessKey = "";
    private final String secretAccessKey = "";

    public Kinesis(String streamName, String region) {
        this.streamName = streamName;
        this.region = Region.of(region);

        /*
         * TODO : Move away from unsafe static AWS credentials. You can add your own credentials
         * for AWS here. Make sure the IAM role attached to these credentials has the correct
         * resource permissions.
         */
        StaticCredentialsProvider credentialsProvider = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretAccessKey));

        this.client = KinesisAsyncClient.builder()
                .credentialsProvider(credentialsProvider)
                .region(this.region)
                .build();

    }

    /**
     * Method which validates the stream to check if it is still open and active.
     * @return true if stream is valid, false otherwise.
     */
    private boolean validateStream() {
        try {
            DescribeStreamRequest describeStreamRequest = DescribeStreamRequest.builder().streamName(streamName).build();
            DescribeStreamResponse describeStreamResponse = client.describeStream(describeStreamRequest).get();

            if (!describeStreamResponse.streamDescription().streamStatus().toString().equals("ACTIVE")) {
                return false;
            } else {
                return true;
            }
        } catch (Exception e) {
            System.out.print("Error during stream validation "+e.toString());
        }

        return false;
    }

    /**
     * Method which writes the acceleration object to the kinesis stream.
     * Does not guarantee that the data will be written.
     * @param acceleration the acceleration object to serialize onto the stream.
     * @return true if the packet was scheduled to be written. False otherwise.
     */
    public boolean writeAccelToStream(Acceleration acceleration) {

        if(acceleration == null) {
            return false;
        }

        // Checks to see if the stream is still active.
        /*
        if(!validateStream()) {
            Log.d("Kinesis", "Stream is inactive.");
            return false;
        }
        */

        String jsonString;
        try {
            jsonString = JSON.writeValueAsString(acceleration);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return false;
        }

        PutRecordRequest request = PutRecordRequest.builder()
                .partitionKey(acceleration.deviceID)
                .streamName(streamName)
                .data(SdkBytes.fromUtf8String(jsonString))
                .build();

        // Async send the packets out. If they fail, then they fail. Not mission critical.
        client.putRecord(request);

        return true;
    }
}
