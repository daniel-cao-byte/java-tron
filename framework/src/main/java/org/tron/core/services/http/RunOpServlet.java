package org.tron.core.services.http;

import com.alibaba.fastjson.JSONObject;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.tron.common.runtime.InternalTransaction;
import org.tron.common.runtime.vm.DataWord;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.vm.JumpTable;
import org.tron.core.vm.Operation;
import org.tron.core.vm.OperationRegistry;
import org.tron.core.vm.program.Program;
import org.tron.core.vm.program.invoke.ProgramInvokeMockImpl;
import org.tron.protos.Protocol;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

@Component
@Slf4j(topic = "API")
public class RunOpServlet extends RateLimiterServlet {

    private final JumpTable jumpTable = OperationRegistry.getTable();

    final Random random = new Random();

    private long cost;

    private int round;

    @SneakyThrows
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        File file=new File("op.json");
        String content= FileUtils.readFileToString(file,"UTF-8");
        JSONObject params = JSONObject.parseObject(content);
        round = params.getIntValue("round");
        Map<String, Object> ops = (Map)params.get("ops");
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss");//设置日期格式
        String date = df.format(new Date());
        mkdir();
        String fileName = "benchmark/output_" + date + "_" + round + ".txt";
        FileWriter fileWriter = new FileWriter(fileName);
        fileWriter.write(String.format("round:%d\n", round));
        for (Map.Entry<String, Object> entry : ops.entrySet()) {
            String opName = entry.getKey();
            logger.info("run op : " + opName);
            Object value = entry.getValue();
            Map<String, Object> map = (Map)value;
            byte[] bytecodes = getBytecodes(map);
            List<String> stacks = getStacks(map);
            cost = 0;
            runOp(bytecodes, stacks);
            long avgCost = cost / round;
            logger.info("run op : " + opName + " cost: " + avgCost);
            fileWriter.write(String.format("%s\t%d\n", opName, avgCost));
        }
        fileWriter.close();
    }

    private void mkdir() {
        File folder = new File("benchmark/");
        if (!folder.exists()) {
            folder.mkdir();
        }
    }

    private void runOp(byte[] bytecodes, List<String> stackValues) throws ContractValidateException {
        for (int i = 0; i < round; i++) {
            ProgramInvokeMockImpl invoke = ProgramInvokeMockImpl.newProgramInvoke();
            Protocol.Transaction trx = Protocol.Transaction.getDefaultInstance();
            InternalTransaction interTrx =
                    new InternalTransaction(trx, InternalTransaction.TrxType.TRX_UNKNOWN_TYPE);
            Program program = new Program(bytecodes, bytecodes, invoke, interTrx);
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

    private byte[] generateAddress() {
        byte[] result = new byte[32];
        random.nextBytes(result);
        result[0] = 0x41;
        return result;
    }

    private byte[] getBytecodes(Map<String, Object> map) {
        List<String> bytecodes  =  (List)map.get("bytecodes");
        byte[] result = new byte[bytecodes.size()];
        for (int i = 0; i < bytecodes.size(); i++) {
            result[i] = Byte.valueOf(bytecodes.get(i), 16);
        }
        return result;
    }

    private List<String> getStacks(Map<String, Object> map) {
        if (map.containsKey("stacks")) {
            return (List)map.get("stacks");
        }
        return Collections.emptyList();
    }

    private void testSingleOpration(Program program) {
        logger.info("run op : balance");
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
        cost += (end - start);
        program.setPreviouslyExecutedOp((byte) op.getOpcode());

    }
}
