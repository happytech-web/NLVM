package ir.value;

import java.util.Objects;

/*
 * maintain the user and usee relation
 * you can take it like a table in MySQL
 * the table looks like: user--usee--index
 */
public class Use {
    private User user;
    private Value usee;
    // which index is the usee in user, for example:
    //   op(a, b, c): user:op, usee:b, index: 1
    private int operandIndex;

    public Use(User user, Value usee, int index) {
        this.user = user;
        this.usee = usee;
        this.operandIndex = index;
    }



    // getter setter
    public User getUser() { return user; }
    public Value getUsee() { return usee; }
    public int getOperandIndex() { return operandIndex; }

    public void setUser(User user) { this.user = user; }
    public void setUsee(Value value) { this.usee = value; }
    public void setOperandIndex(int idx) { this.operandIndex = idx; }



    // override
    @Override
    public boolean equals(Object obj) {
        if (obj instanceof Use use) {
            return use.operandIndex == operandIndex
                && user.equals(use.user)
                && usee.equals(use.usee);
        } else {
            return false;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(operandIndex, user, usee);
    }

    @Override
    public String toString() {
        return "Use(" + user.getName() + " -> " + usee.getReference() + ", index=" + operandIndex + ")";
    }

}
