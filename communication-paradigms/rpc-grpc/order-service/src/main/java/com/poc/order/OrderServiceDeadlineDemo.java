package com.poc.order;

import com.poc.inventory.grpc.InventoryServiceGrpc;
import com.poc.inventory.grpc.ReserveRequest;
import com.poc.inventory.grpc.ReserveResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;

import java.util.concurrent.TimeUnit;

public class OrderServiceDeadlineDemo {
    public static void main(String[] args) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext()
                .build();

        InventoryServiceGrpc.InventoryServiceBlockingStub stub =
                InventoryServiceGrpc.newBlockingStub(channel)
                        .withDeadlineAfter(2, TimeUnit.SECONDS); // give up after 2 seconds

        ReserveRequest request = ReserveRequest.newBuilder()
                .setItemId("item-slow")
                .setQuantity(1)
                .build();

        long start = System.currentTimeMillis();
        System.out.println("Sending request for item-slow with a 2-second deadline (server will stall 10s)...");

        try {
            ReserveResponse response = stub.checkAndReserve(request);
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("Response received after " + elapsed + "ms: " + response.getMessage());
        } catch (StatusRuntimeException e) {
            long elapsed = System.currentTimeMillis() - start;
            System.out.println("Call failed after " + elapsed + "ms with status: " + e.getStatus().getCode());
            System.out.println("OrderService can now react immediately instead of hanging for 10s.");
        }

        channel.shutdown();
    }
}