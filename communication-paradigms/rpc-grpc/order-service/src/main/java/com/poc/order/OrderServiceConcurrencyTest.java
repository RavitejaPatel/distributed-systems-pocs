package com.poc.order;

import com.poc.inventory.grpc.InventoryServiceGrpc;
import com.poc.inventory.grpc.ReserveRequest;
import com.poc.inventory.grpc.ReserveResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class OrderServiceConcurrencyTest {
    public static void main(String[] args) throws InterruptedException {
        int threadCount = 20; // 20 "users" trying to buy the same item at once

        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        AtomicInteger successCount = new AtomicInteger();
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    ManagedChannel channel = ManagedChannelBuilder
                            .forAddress("localhost", 50051)
                            .usePlaintext()
                            .build();

                    InventoryServiceGrpc.InventoryServiceBlockingStub stub =
                            InventoryServiceGrpc.newBlockingStub(channel);

                    ReserveRequest request = ReserveRequest.newBuilder()
                            .setItemId("item-1")
                            .setQuantity(1)
                            .build();

                    ReserveResponse response = stub.checkAndReserve(request);

                    if (response.getSuccess()) {
                        successCount.incrementAndGet();
                    }

                    channel.shutdown();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        pool.shutdown();

        System.out.println("Successful reservations: " + successCount.get() + " out of " + threadCount + " (stock started at 1)");
    }
}