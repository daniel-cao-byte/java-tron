package org.tron.core.services.http;

import com.alibaba.fastjson.JSONObject;
import org.apache.commons.io.FileUtils;
import org.bouncycastle.util.encoders.Hex;
import org.eclipse.jetty.util.StringUtil;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.StoreFactory;
import org.tron.core.vm.JumpTable;
import org.tron.core.vm.Operation;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.invoke.ProgramInvokeMockImpl;
import org.tron.protos.Protocol;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

public abstract class OpServlet extends RateLimiterServlet{

    protected String opConfig;

    protected String fileName;

    protected int round;

    protected FileWriter fileWriter;

    protected Map<String, Object> ops;

    final Random random = new Random();

    protected final JumpTable jumpTable = OperationRegistry.getTable();

    protected long cost;

    protected List<Long> costList;

    protected void parseConfig(HttpServletRequest request) throws IOException {
        opConfig = request.getParameter("op_config");
        if (StringUtil.isBlank(opConfig)) {
            opConfig = "op";
        }
        File file=new File(opConfig + ".json");
        String content= FileUtils.readFileToString(file,"UTF-8");
        JSONObject params = JSONObject.parseObject(content);

        round = params.getIntValue("round");
        fileName = params.getString("fileName");
        File folder = new File("benchmark/");
        if (!folder.exists()) {
            folder.mkdir();
        }


        if (fileName == null) {
            fileName = "benchmark/output_" + opConfig + ".txt";
        }
        else {
            fileName = "benchmark/" + fileName + ".txt";
        }
        fileWriter = new FileWriter(fileName, true);
        ops = (Map)params.get("ops");
    }

    protected byte[] generateAddress() {
        byte[] result = new byte[32];
        random.nextBytes(result);
        result[0] = 0x41;
        return result;
    }

    protected byte[] getBytecodes(Map<String, Object> map) {
        String bytecodes = (String) map.get("bytecodes");
        return Hex.decode(bytecodes);
    }

    protected byte[] getCodeAddress(Map<String, Object> map) {
        String codeAddress = (String) map.get("codeAddress");
        if (codeAddress == null) {
            return new byte[0];
        }
        return Hex.decode(codeAddress);
    }


    protected List<String> getStacks(Map<String, Object> map) {
        if (map.containsKey("stacks")) {
            return (List)map.get("stacks");
        }
        return Collections.emptyList();
    }

    protected void runOp(byte[] bytecodes, byte[] codeAddress, List<String> stackValues) throws ContractValidateException {
        for (int i = 0; i < round; i++) {
            ProgramInvokeMockImpl invoke = new ProgramInvokeMockImpl(StoreFactory.getInstance(), bytecodes, codeAddress);
            Protocol.Transaction trx = Protocol.Transaction.getDefaultInstance();
            InternalTransaction interTrx =
                    new InternalTransaction(trx, InternalTransaction.TrxType.TRX_UNKNOWN_TYPE);
            Program program = new Program(bytecodes, codeAddress, invoke, interTrx);
            for (String value : stackValues) {
                if (value.equals("randomAddress")) {
                    program.stackPush(new DataWord(generateAddress()));
                }
                else {
                    program.stackPush(new DataWord(value));
                }
            }
            testSingleOpration(program);
        }
    }

    protected void testSingleOpration(Program program) {
        Operation op = jumpTable.get(program.getCurrentOpIntValue());
        if (!op.isEnabled()) {
            throw Program.Exception.invalidOpCode(program.getCurrentOp());
        }
        program.setLastOp((byte) op.getOpcode());
        program.verifyStackSize(op.getRequire());
        program.verifyStackOverflow(op.getRequire(), op.getRet());
        long start = System.nanoTime();
        op.execute(program);
        long end = System.nanoTime();
        long curCost = end - start;
        if (costList != null) {
            costList.add(curCost);
        }
        cost += curCost;
        program.setPreviouslyExecutedOp((byte) op.getOpcode());
    }
}
