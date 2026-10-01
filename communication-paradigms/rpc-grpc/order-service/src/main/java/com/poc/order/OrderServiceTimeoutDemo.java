package com.poc.order;

import com.poc.inventory.grpc.InventoryServiceGrpc;
import com.poc.inventory.grpc.ReserveRequest;
import com.poc.inventory.grpc.ReserveResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class OrderServiceTimeoutDemo {
    public static void main(String[] args) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext()
                .build();

        InventoryServiceGrpc.InventoryServiceBlockingStub stub =
                InventoryServiceGrpc.newBlockingStub(channel);

        ReserveRequest request = ReserveRequest.newBuilder()
                .setItemId("item-slow")
                .setQuantity(1)
                .build();

        long start = System.currentTimeMillis();
        System.out.println("Sending request for item-slow (server will simulate a 10s stall)...");

        // NOTE: no deadline set here — this call will wait as long as the server takes.
        ReserveResponse response = stub.checkAndReserve(request);

        long elapsed = System.currentTimeMillis() - start;
        System.out.println("Response received after " + elapsed + "ms: " + response.getMessage());

        channel.shutdown();
    }
}