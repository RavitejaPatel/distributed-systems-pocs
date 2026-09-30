package com.poc.order;

import com.poc.inventory.grpc.InventoryServiceGrpc;
import com.poc.inventory.grpc.ReserveRequest;
import com.poc.inventory.grpc.ReserveResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

public class OrderServiceApp {
    public static void main(String[] args) throws Exception {

        // Open a connection to InventoryService running on localhost:50051
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext()  // no TLS for this local POC
                .build();

        InventoryServiceGrpc.InventoryServiceBlockingStub stub =
                InventoryServiceGrpc.newBlockingStub(channel);

        ReserveRequest request = ReserveRequest.newBuilder()
                .setItemId("item-1")
                .setQuantity(2)
                .build();

        System.out.println("Sending request: item=" + request.getItemId() + ", qty=" + request.getQuantity());

        // This line BLOCKS until InventoryService responds — the defining behavior of RPC
        ReserveResponse response = stub.checkAndReserve(request);

        System.out.println("Response received:");
        System.out.println("  success: " + response.getSuccess());
        System.out.println("  message: " + response.getMessage());
        System.out.println("  remainingStock: " + response.getRemainingStock());

        channel.shutdown();
    }
}