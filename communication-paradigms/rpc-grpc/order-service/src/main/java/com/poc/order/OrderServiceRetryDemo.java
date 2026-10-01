package com.poc.order;

import com.poc.inventory.grpc.InventoryServiceGrpc;
import com.poc.inventory.grpc.ReserveRequest;
import com.poc.inventory.grpc.ReserveResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.UUID;

public class OrderServiceRetryDemo {
    public static void main(String[] args) throws Exception {
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext()
                .build();

        InventoryServiceGrpc.InventoryServiceBlockingStub stub =
                InventoryServiceGrpc.newBlockingStub(channel);

        // Generated ONCE per logical order — reused across every retry attempt
        // for that same order, which is what makes retries safe.
        String idempotencyKey = UUID.randomUUID().toString();

        ReserveRequest request = ReserveRequest.newBuilder()
                .setItemId("item-1")
                .setQuantity(1)
                .setIdempotencyKey(idempotencyKey)
                .build();

        System.out.println("Idempotency key for this order: " + idempotencyKey);

        System.out.println("\n--- Attempt 1 (simulating the original request) ---");
        ReserveResponse first = stub.checkAndReserve(request);
        System.out.println("success=" + first.getSuccess() + ", message=" + first.getMessage() + ", remainingStock=" + first.getRemainingStock());

        System.out.println("\n--- Attempt 2 (simulating a client retry after a lost/timed-out response) ---");
        ReserveResponse retry = stub.checkAndReserve(request); // SAME request, same idempotency key
        System.out.println("success=" + retry.getSuccess() + ", message=" + retry.getMessage() + ", remainingStock=" + retry.getRemainingStock());

        channel.shutdown();
    }
}