package com.zzypiper.module;

import java.util.ArrayList;
import java.util.List;

public class Session {
    private final List<Message> messages = new ArrayList<>();

    public void addMessage(Message message){
        messages.add(message);
    }

    public List<Message> getMessages(){
        return messages;
    }

    public int size(){
        return messages.size();
    }
}
