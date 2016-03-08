package org.opendaylight.controller.connectionmanager.loadbalancer;

public class ExitStatus {
    private boolean ok;
    private String message;
    public ExitStatus(boolean ok,String msg){
        this.ok=ok;
        this.message=msg;  
    }
    public ExitStatus(boolean ok){
        this.ok=ok;
    }
    public boolean isOk() {
        return ok;
    }
    public String getMessage() {
        return message;
    }
    @Override
    public String toString() {
        return "ExitStatus [ok=" + ok + ", message=" + message + "]";
    }
    
}
