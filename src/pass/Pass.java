package pass;


public interface Pass {
    // just a mark class for future change

    public interface IRPass extends Pass {
        // TODO: change the interface base on what we need
        //       may be we need to add some context in the run method
        IRPassType getType();
        void run();
    }

    public interface MCPass extends Pass {
        // TODO: change the interface base on what we need
        //       may be we need to add some context in the run method
        MCPassType getType();
        void run();
    }
}
