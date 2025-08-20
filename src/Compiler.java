import driver.*;

public class Compiler {
    /*
     * move the duty of compiler to driver,
     * for we can't use package here
     */
    public static void main(String[] args) {
        CompilerDriver driver = CompilerDriver.getInstance();
        driver.parseArgs(args);
        driver.run();
    }
}
