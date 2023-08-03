package och.coin;

import com.codebrig.beam.messages.BasicMessage;
import com.codebrig.beam.messages.BeamMessage;
import com.dosse.upnp.UPnP;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bouncycastle.util.encoders.Base64Encoder;
import org.OchCoin.blocks.Block;
import org.OchCoin.blocks.MempoolBlock;
import org.OchCoin.miner.VerificationUtils;
import org.OchCoin.p2p.Peer;
import org.OchCoin.p2p.server.PeerServer;
import org.OchCoin.transactions.Transaction;
import org.OchCoin.wallet.SignageUtils;

import java.io.*;
import java.security.Security;
import java.util.Base64;
import java.util.Scanner;

import static org.OchCoin.OchCoinCache.*;
import static org.OchCoin.p2p.RequestParams.*;

public class OchCoin {


    private void startMining() throws IOException {
        Block mine = OchCoinCache.getTxStatus().block;
        mine.difficulty = getBlockDifficulty();
        mine.transactions.add(new Transaction("coinbase", wallet.getBase64Key(wallet.publicKey),50.f,"null","FUNNY"));
        int diff = getDifficulty;
        if(mine.mine(diff)) {
            Gson gson = new Gson();
            String json = gson.toJson(mine);
            peerServer.broadcast(json, "newBlock");
            blockChain.add(mine);
            syncBlockchainFile();
            interrupted = false;
        }
    }

    private void loadConfig() {
        try {
            final File config = new File("config.json");
            final StringBuilder builder = new StringBuilder();
            final BufferedReader reader = new BufferedReader(new FileReader(config));
            String tmp;
            while((tmp = reader.readLine()) != null) builder.append(tmp);
            JsonParser parser = new JsonParser();
            JsonObject obj = (JsonObject) parser.parse(builder.toString());
            OchCoinCache.port = obj.get("port").getAsInt();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    OchCoin(NodeType type) throws Exception {
        Gson gson = new Gson();
        if(type == NodeType.MINER) {
            OchCoinCache.blk = new MempoolBlock(new Block("null"),false);
            // NetworkManager manager = new NetworkManager();
            System.out.println("connecting to other people");
            loadConfig();
            loadBlockChain();
            if(blockChain.size() == 0) {
                peerServer.init();
                peerLoader.init();
                if(peerLoader.peers.size() > 0) {
                    for (int k = 0; k < peerLoader.peers.size(); k++) {
                        Peer p = peerLoader.peers.get(k);
                        BeamMessage message = new BeamMessage();
                        message.set("event", "nodejoin");
                        message.set("address", getIp());
                        message.set("port", String.valueOf(peerServer.port));
                        p.socket.queueMessage(message);
                    }
                }
                Block genesis = OchCoinCache.getCurrentBlock();
                genesis.transactions.add(new Transaction("coinbase",wallet.getBase64Key(wallet.publicKey),50.0f,"null","OchCoin"));
                if(genesis.mine(getBlockDifficulty())) {
                    blockChain.add(genesis);
                    syncBlockchainFile();
                    /**
                     * We have set the local blockchain since we know our own block is valid at genesis. who else would make a fraudulent block when they don't know the chain exists;
                     * we are going to send the block to other people now.
                     */
                    peerServer.broadcast(gson.toJson(genesis),"newBlock");
                }
                while (true) {
                    mine();
                }
            } else {
                peerServer.init();
                peerLoader.init();
                for(Peer p : peerLoader.peers) {
                    BeamMessage message = new BeamMessage();
                    message.set("event","nodejoin");
                    message.set("address", getIp());
                    message.set("port", String.valueOf(peerServer.port));
                    p.socket.queueMessage(message);
                }

                while(true) {
                    mine();
                }
            }
        } else if(type == NodeType.WALLET) {

            OchCoinCache.loadBlockChain();
            peerLoader.init();
            while(true) {
                Scanner p = new Scanner(System.in);
                String l = p.nextLine();
                if(l != null) {
                    String[] args = l.split(" ");
                    if(args[0].equals("send") && (Float.parseFloat(args[2]) < getBalanceFromChain(wallet.getBase64Key(wallet.publicKey),args[3]))) {
                        JsonObject object = new JsonObject();
                        object.addProperty("ownerWallet",wallet.getBase64Key(wallet.publicKey));
                        object.addProperty("targetWallet",args[1]);
                        object.addProperty("amount",Float.parseFloat(args[2]));
                        object.addProperty("token",args[3]);
                        object.addProperty("version",1);

                        String txHash = SignageUtils.applySha256(wallet.getBase64Key(wallet.publicKey) + args[1] + args[2] + args[3] + 1);
                        String signature = SignageUtils.sign(txHash,wallet.privateKey);
                        object.addProperty("signature", signature);
                        for(Peer a : peerLoader.peers) {
                            BasicMessage beamMessage = new BasicMessage();
                            beamMessage = (BasicMessage) beamMessage.set("message",object.toString());
                            beamMessage = (BasicMessage) beamMessage.set("event","newTransaction");

                            a.socket.queueMessage(beamMessage);
                        }
                    }
                }
            }
        }
    }

    private float getBalanceFromChain(String publicKey, String token) {
        token = token.toUpperCase();
        float balance = 0.0f;
        for(Block block : OchCoinCache.blockChain) {
            for(Transaction transaction : block.getTransactions()) {
                if((transaction.getOutputKey().contains(publicKey)) && transaction.getToken().equals(token)) {
                    balance += transaction.getAmount();
                }
            }
        }
        return balance;
    }

    public static void main(String[] args) throws Exception {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        if(args.length == 1) {
            if(args[0].toLowerCase().contains("miner")) {
                new OchCoin(NodeType.MINER);
            } else if(args[0].toLowerCase().contains("wallet")) {
                new OchCoin(NodeType.WALLET);
            }
        }
    }





    public enum NodeType {
        MINER,
        WALLET
    }
}
