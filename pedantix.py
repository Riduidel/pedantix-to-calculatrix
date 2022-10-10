import requests
import codecs
import json
import pyautogui
import pyperclip

def main():
    words = list()
    with codecs.open("mots_frquents.txt", "r", "utf-8") as f:
        words = f.readlines()
        for text in words[:]:
            # Don't forget this prefix is the pin prefix, not the classical one
            text_field_location_prefix = pyautogui.locateOnScreen("pedantix_word_text_field_prefix.png")
            if not text_field_location_prefix:
                raise Exception("Can't find the pinned prefix. Is the pedantix window unpinned?")
            text_field_location_suffix = pyautogui.locateOnScreen("pedantix_word_text_field_suffix.png")
            if not text_field_location_prefix:
                raise Exception("Unable to locate prefix")
                
            if not text_field_location_suffix:
                raise Exception("Unable to locate suffix")
            x = (text_field_location_prefix.left+text_field_location_suffix.left+text_field_location_suffix.width)/2
            y = (text_field_location_prefix.top+text_field_location_suffix.top+text_field_location_suffix.height)/2
#            print("Prefix is at %s\nSuffix is at %s\nSeems like text field should be at (%d, %d)" %
#                (text_field_location_prefix, text_field_location_suffix, x, y))
            text = text.strip()
            pyautogui.click(x, y)
            # Mind you, pyautogui only handle keyboard keys, and not french accents
            # So I prefer to copy word in clipboard, then paste it
            print("Testing \"%s\""%(text))
            pyperclip.copy(text)
            pyautogui.click()
            pyautogui.hotkey("ctrl", "v")
            pyautogui.press('enter')
            # Now analyze word ranking

if __name__ == "__main__":
    main()
