package com.poc.inventory;

import com.poc.inventory.grpc.InventoryServiceGrpc;
import com.poc.inventory.grpc.ReserveRequest;
import com.poc.inventory.grpc.ReserveResponse;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;

public class InventoryServiceApp {

    // In-memory stock: one item, starting quantity 1. No persistence yet.
    private static int stock = 1;

    public static void main(String[] args) throws Exception {
        int port = 50051;

        Server server = ServerBuilder.forPort(port)
                .addService(new InventoryServiceImpl())
                .build();

        server.start();
        System.out.println("InventoryService listening on port " + port + " (stock = " + stock + ")");

        server.awaitTermination();
    }

    // The actual RPC implementation — this is the "function" OrderService calls remotely.
    static class InventoryServiceImpl extends InventoryServiceGrpc.InventoryServiceImplBase {

        @Override
        public void checkAndReserve(ReserveRequest request, StreamObserver<ReserveResponse> responseObserver) {
            System.out.println("Received request: item=" + request.getItemId() + ", qty=" + request.getQuantity());
            
            // Simulate a slow downstream dependency (e.g. a hung database call)
            // for a specific item, to demonstrate what happens without a deadline.
            if (request.getItemId().equals("item-slow")) {
                try {
                    System.out.println("Simulating a slow dependency... sleeping 10 seconds");
                    Thread.sleep(10000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }   
            ReserveResponse response;

            // synchronized ensures only one thread at a time can execute this block,
            // closing the race window between checking stock and decrementing it.
            synchronized (InventoryServiceApp.class) {
                if (stock >= request.getQuantity()) {
                    try {
                        Thread.sleep(50); // artificially widen the race window for demonstration
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    stock -= request.getQuantity();
                    response = ReserveResponse.newBuilder()
                            .setSuccess(true)
                            .setMessage("Reserved successfully")
                            .setRemainingStock(stock)
                            .build();
                } else {
                    response = ReserveResponse.newBuilder()
                            .setSuccess(false)
                            .setMessage("Insufficient stock")
                            .setRemainingStock(stock)
                            .build();
                }
            }

            responseObserver.onNext(response);   // send the response back to the caller
            responseObserver.onCompleted();       // signal the RPC is done
        }
    }
}