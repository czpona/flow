package com.vaadin.tests.components.uitest;

import com.vaadin.server.VaadinRequest;
import com.vaadin.tests.components.AbstractTestUI;
import com.vaadin.ui.Button;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Notification;
import com.vaadin.ui.UI;

public class UIScrolling extends AbstractTestUI {

    int scrollTop = 0, scrollLeft = 0;

    @Override
    protected void setup(VaadinRequest request) {

        // Set layout to high enough to get scroll.
        getLayout().setHeight("2250px");

        getElement().addEventData("scroll", "scrollTop", "scrollLeft");
        getElement().addEventListener("scroll", e -> {
            scrollTop = (int) e.getNumber("scrollTop");
            scrollLeft = (int) e.getNumber("scrollLeft");
        });
        add(new Button("scroll to 1000px", new Button.ClickListener() {

            @Override
            public void buttonClick(ClickEvent event) {
                UI.getCurrent().setScrollTop(1000);
            }
        }));
        add(new Button(
                "This button is halfway down. Click to report scroll position.",
                new Button.ClickListener() {
                    @Override
                    public void buttonClick(ClickEvent event) {
                        Notification.show("Scrolled to " + scrollTop + " px");
                    }
                }));
    }

    @Override
    protected String getTestDescription() {
        return "Windows can be programmatically scrolled";
    }

    @Override
    protected Integer getTicketNumber() {
        return 9952;
    }

}
