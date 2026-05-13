package run.endive.fuzz;

public class TestResult {

    private String oracleResult;
    private String endiveResult;

    public TestResult(String oracleResult, String endiveResult) {
        this.oracleResult = oracleResult;
        this.endiveResult = endiveResult;
    }

    public String getOracleResult() {
        return oracleResult;
    }

    public String getChicoryResult() {
        return endiveResult;
    }
}
